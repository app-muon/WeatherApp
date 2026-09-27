package com.example.weatherapp.data.repository

import com.example.weatherapp.data.db.LocationEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant

enum class ContentScope { Forecast, Compare, Marine }

data class RefreshRequest(
    val locationIds: List<Long>,
    val scope: ContentScope,
    val force: Boolean = false,
    val maxAge: Duration = FOREGROUND_MAX_AGE,
    val skipPermanentFailures: Boolean = false
)

val FOREGROUND_MAX_AGE: Duration = Duration.ofMinutes(30)
val BACKGROUND_MAX_AGE: Duration = Duration.ofHours(3)
const val MARINE_SOURCE = "marine"
private const val STORAGE_ERROR = "Unable to read or save weather data. Please try again."

fun isFresh(fetchedAt: Instant?, now: Instant, maxAge: Duration): Boolean =
    fetchedAt != null && !fetchedAt.isAfter(now) && Duration.between(fetchedAt, now) < maxAge

enum class RefreshResult { Fresh, Updated, Unavailable, Failed, Discarded }
data class SourceOutcome(
    val locationId: Long,
    val sourceId: String,
    val result: RefreshResult,
    val message: String? = null,
    val retryable: Boolean = false,
    val unexpectedFailure: Boolean = false
) {
    val usable: Boolean get() = result == RefreshResult.Fresh || result == RefreshResult.Updated
}

data class RefreshKey(val locationId: Long, val revision: Long, val scope: ContentScope)
data class SourceKey(val locationId: Long, val revision: Long, val sourceId: String)
data class RefreshActivity(
    val scopes: Map<RefreshKey, Int> = emptyMap(),
    val sources: Set<SourceKey> = emptySet(),
    val failures: Map<RefreshKey, String> = emptyMap()
) {
    fun isActive(location: LocationEntity, scope: ContentScope): Boolean =
        (scopes[RefreshKey(location.id, location.revision, scope)] ?: 0) > 0

    fun failure(location: LocationEntity, scope: ContentScope): String? =
        failures[RefreshKey(location.id, location.revision, scope)] ?: failures[RefreshKey(location.id, -1, scope)]
}

/** Storage owns atomic revision checks; the coordinator owns scheduling and cancellation. */
interface RefreshStore {
    suspend fun location(id: Long): LocationEntity?
    suspend fun sources(location: LocationEntity, scope: ContentScope): List<String>
    suspend fun cachedAt(location: LocationEntity, source: String): Instant?
    suspend fun unavailableAt(location: LocationEntity, source: String): Instant? = null
    suspend fun isPermanentFailure(location: LocationEntity, source: String): Boolean = false
    suspend fun fetch(location: LocationEntity, source: String): SourceOutcome
}

class RefreshCoordinator(
    private val store: RefreshStore,
    private val applicationScope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val onUnexpectedError: (Exception) -> Unit = {}
) {
    private class InFlight(val task: Deferred<SourceOutcome>, var callers: Int = 0)
    private val mutex = Mutex()
    private val requests = mutableMapOf<SourceKey, InFlight>()
    private val permits = Semaphore(3)
    private val _activity = MutableStateFlow(RefreshActivity())
    val activity = _activity.asStateFlow()

    suspend fun refresh(request: RefreshRequest): List<SourceOutcome> = coroutineScope {
        request.locationIds.distinct().map { id -> async { refreshLocation(id, request) } }.awaitAll().flatten()
    }

    private suspend fun refreshLocation(id: Long, request: RefreshRequest): List<SourceOutcome> {
        var key = RefreshKey(id, -1, request.scope)
        var registered = false
        try {
            val location = store.location(id) ?: return emptyList()
            key = RefreshKey(id, location.revision, request.scope)
            changeScope(key, 1)
            registered = true
            val sources = store.sources(location, request.scope)
            val outcomes = if (request.scope == ContentScope.Compare) {
                coroutineScope { sources.map { source -> async { refreshSource(location, source, request) } }.awaitAll() }
            } else {
                buildList {
                    for (source in sources) {
                        val outcome = refreshSource(location, source, request)
                        add(outcome)
                        if (outcome.usable || outcome.result == RefreshResult.Discarded || outcome.result == RefreshResult.Unavailable) break
                    }
                }
            }
            if (outcomes.any { it.unexpectedFailure } &&
                (request.scope == ContentScope.Compare || outcomes.none { it.usable })) recordFailure(key)
            return outcomes
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            onUnexpectedError(error)
            recordFailure(key)
            return listOf(SourceOutcome(id, "refresh", RefreshResult.Failed, STORAGE_ERROR, retryable = true, unexpectedFailure = true))
        } finally {
            if (registered) withContext(NonCancellable) { changeScope(key, -1) }
        }
    }

    private suspend fun recordFailure(key: RefreshKey) = mutex.withLock {
        _activity.value = _activity.value.copy(failures = _activity.value.failures + (key to STORAGE_ERROR))
    }

    private suspend fun changeScope(key: RefreshKey, delta: Int) = mutex.withLock {
        val counts = _activity.value.scopes.toMutableMap()
        val count = (counts[key] ?: 0) + delta
        if (count > 0) counts[key] = count else counts.remove(key)
        val failures = if (delta > 0) _activity.value.failures.filterKeys { it.locationId != key.locationId || it.scope != key.scope }
            else _activity.value.failures
        _activity.value = _activity.value.copy(scopes = counts, failures = failures)
    }

    private suspend fun protect(locationId: Long, source: String, block: suspend () -> SourceOutcome): SourceOutcome = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        onUnexpectedError(error)
        SourceOutcome(locationId, source, RefreshResult.Failed, STORAGE_ERROR, retryable = true, unexpectedFailure = true)
    }

    private suspend fun refreshSource(location: LocationEntity, source: String, request: RefreshRequest): SourceOutcome =
        protect(location.id, source) { refreshSourceRequest(location, source, request) }

    private suspend fun refreshSourceRequest(location: LocationEntity, source: String, request: RefreshRequest): SourceOutcome {
        val key = SourceKey(location.id, location.revision, source)
        val existing = mutex.withLock {
            requests[key]?.takeIf { !it.task.isCompleted }?.also { register(key, it) }
        }
        if (existing != null) return awaitSource(key, existing)
        if (request.skipPermanentFailures && store.isPermanentFailure(location, source)) {
            return SourceOutcome(location.id, source, RefreshResult.Failed)
        }
        // A cache lookup is not a network request: a manual refresh must never join
        // a freshness-only check and accidentally skip its forced download.
        if (!request.force) {
            if (isFresh(store.unavailableAt(location, source), clock.instant(), request.maxAge)) {
                return SourceOutcome(location.id, source, RefreshResult.Unavailable)
            }
            if (isFresh(store.cachedAt(location, source), clock.instant(), request.maxAge)) {
                return SourceOutcome(location.id, source, RefreshResult.Fresh)
            }
        }
        val running = mutex.withLock {
            val flight = requests[key]?.takeIf { !it.task.isCompleted } ?: InFlight(
                applicationScope.async(start = CoroutineStart.LAZY) {
                    protect(location.id, source) { permits.withPermit { store.fetch(location, source) } }
                }
            ).also { requests[key] = it }
            register(key, flight)
            flight
        }
        return awaitSource(key, running)
    }

    /** Called only while holding mutex. */
    private fun register(key: SourceKey, flight: InFlight) {
        flight.callers++
        _activity.value = _activity.value.copy(sources = _activity.value.sources + key)
        flight.task.start()
    }

    private suspend fun awaitSource(key: SourceKey, running: InFlight): SourceOutcome {
        try {
            return running.task.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    running.callers--
                    if (running.callers == 0) {
                        if (requests[key] === running) {
                            requests.remove(key)
                            _activity.value = _activity.value.copy(sources = _activity.value.sources - key)
                        }
                        running.task.cancel()
                    }
                }
            }
        }
    }
}
