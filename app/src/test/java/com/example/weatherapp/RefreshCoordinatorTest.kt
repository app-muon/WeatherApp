package com.example.weatherapp

import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.data.repository.*
import com.example.weatherapp.worker.shouldRetryRefresh
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshCoordinatorTest {
    private val now = Instant.parse("2026-09-27T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test fun freshnessBoundariesAndFutureTimestamps() {
        assertTrue(isFresh(now.minusSeconds(1799), now, FOREGROUND_MAX_AGE))
        assertFalse(isFresh(now.minusSeconds(1800), now, FOREGROUND_MAX_AGE))
        assertTrue(isFresh(now.minusSeconds(10799), now, BACKGROUND_MAX_AGE))
        assertFalse(isFresh(now.minusSeconds(10800), now, BACKGROUND_MAX_AGE))
        assertFalse(isFresh(null, now, FOREGROUND_MAX_AGE))
        assertFalse(isFresh(now.plusSeconds(1), now, FOREGROUND_MAX_AGE))
    }

    @Test fun visibleScopesAndRepeatedEntryUseIndependentFreshness() = runTest {
        val store = FakeStore(now)
        val coordinator = RefreshCoordinator(store, this, clock)
        store.cached[1L to "preferred"] = now
        coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Forecast))
        assertTrue(store.calls.isEmpty())
        coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Compare))
        assertEquals(listOf("fallback"), store.calls.map { it.sourceId })
        coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Compare))
        assertEquals(1, store.calls.size)
        store.cached[1L to "preferred"] = now.minus(Duration.ofMinutes(31))
        coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Compare))
        assertEquals(listOf("fallback", "preferred"), store.calls.map { it.sourceId })
        coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Marine, force = true))
        assertEquals(MARINE_SOURCE, store.calls.last().sourceId)
        assertEquals(3, store.calls.size)
        coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Forecast, force = true))
        assertEquals("preferred", store.calls.last().sourceId)
        assertEquals(4, store.calls.size)
    }

    @Test fun appAndWorkerJoinAnEquivalentNetworkRequest() = runTest {
        val store = FakeStore(now)
        val gate = CompletableDeferred<Unit>()
        store.gate = { gate.await() }
        val coordinator = RefreshCoordinator(store, this, clock)
        val app = async { coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Forecast, true)) }
        runCurrent()
        val worker = async { coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Forecast, maxAge = BACKGROUND_MAX_AGE)) }
        runCurrent()
        assertEquals(1, store.calls.size)
        gate.complete(Unit)
        assertEquals(RefreshResult.Updated, app.await().single().result)
        assertEquals(app.await(), worker.await())
        assertEquals(RefreshActivity(), coordinator.activity.value)
    }

    @Test fun manualRefreshDoesNotJoinAFreshnessOnlyLookup() = runTest {
        val store = FakeStore(now)
        val lookup = CompletableDeferred<Unit>()
        store.cached[1L to "preferred"] = now
        store.cacheGate = { lookup.await() }
        val coordinator = RefreshCoordinator(store, this, clock)
        val automatic = async { coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Forecast)) }
        runCurrent()
        val manual = coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Forecast, true))
        assertEquals(RefreshResult.Updated, manual.single().result)
        assertEquals(1, store.calls.size)
        lookup.complete(Unit)
        automatic.await()
    }

    @Test fun cancellationOfOneCallerKeepsSharedRequestAlive() = runTest {
        val store = FakeStore(now)
        val gate = CompletableDeferred<Unit>()
        store.gate = { gate.await() }
        val coordinator = RefreshCoordinator(store, this, clock)
        val app = async { coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Forecast, true)) }
        runCurrent()
        val worker = async { coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Forecast, true)) }
        runCurrent()
        app.cancelAndJoin()
        assertEquals(0, store.cancelled)
        assertTrue(coordinator.activity.value.isActive(store.locations.getValue(1), ContentScope.Forecast))
        gate.complete(Unit)
        assertTrue(worker.await().single().usable)
        assertEquals(RefreshActivity(), coordinator.activity.value)
    }

    @Test fun lastCallerCancellationPropagatesAndClearsState() = runTest {
        val store = FakeStore(now)
        store.gate = { awaitCancellation() }
        val coordinator = RefreshCoordinator(store, this, clock)
        val job = launch { coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Marine, true)) }
        runCurrent()
        job.cancelAndJoin()
        runCurrent()
        assertEquals(1, store.cancelled)
        assertEquals(RefreshActivity(), coordinator.activity.value)
        assertTrue(store.cached.isEmpty())
    }

    @Test fun reversedCompletionDoesNotClearOtherLocationsOrMarine() = runTest {
        val store = FakeStore(now, 2)
        val gates = (listOf("preferred", MARINE_SOURCE)).associateWith { CompletableDeferred<Unit>() }
        store.gate = { gates.getValue(it.sourceId).await() }
        val coordinator = RefreshCoordinator(store, this, clock)
        val weather = async { coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Forecast, true)) }
        val marine = async { coordinator.refresh(RefreshRequest(listOf(2), ContentScope.Marine, true)) }
        runCurrent()
        gates.getValue(MARINE_SOURCE).complete(Unit)
        marine.await()
        assertTrue(coordinator.activity.value.isActive(store.locations.getValue(1), ContentScope.Forecast))
        assertFalse(coordinator.activity.value.isActive(store.locations.getValue(2), ContentScope.Marine))
        gates.getValue("preferred").complete(Unit)
        weather.await()
        assertEquals(RefreshActivity(), coordinator.activity.value)
    }

    @Test fun concurrencyIsCappedAtThree() = runTest {
        val store = FakeStore(now, 4)
        val gate = CompletableDeferred<Unit>()
        store.gate = { gate.await() }
        val coordinator = RefreshCoordinator(store, this, clock)
        val result = async { coordinator.refresh(RefreshRequest(listOf(1, 2, 3, 4), ContentScope.Compare, true)) }
        runCurrent()
        assertEquals(3, store.calls.size)
        gate.complete(Unit)
        assertEquals(8, result.await().size)
        assertEquals(3, store.peak)
    }

    @Test fun fallbackRemainsSequentialAndReportsEachOutcome() = runTest {
        val store = FakeStore(now)
        store.failures += "preferred"
        val coordinator = RefreshCoordinator(store, this, clock)
        val outcomes = coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Forecast, true))
        assertEquals(listOf("preferred", "fallback"), store.calls.map { it.sourceId })
        assertEquals(listOf(RefreshResult.Failed, RefreshResult.Updated), outcomes.map { it.result })
        assertEquals(1, store.peak)
    }

    @Test fun newRevisionDoesNotJoinPreviousPlace() = runTest {
        val store = FakeStore(now)
        val oldGate = CompletableDeferred<Unit>()
        store.gate = { if (it.revision == 0L) oldGate.await() }
        val coordinator = RefreshCoordinator(store, this, clock)
        val old = async { coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Forecast, true)) }
        runCurrent()
        store.locations[1] = store.locations.getValue(1).copy(revision = 1)
        assertTrue(coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Forecast, true)).single().usable)
        oldGate.complete(Unit)
        assertEquals(RefreshResult.Discarded, old.await().single().result)
        assertEquals(listOf(0L, 1L), store.calls.map { it.revision })
    }

    @Test fun unavailableMarineUsesFreshnessWindowAndManualRefreshBypassesIt() = runTest {
        val store = FakeStore(now)
        store.unavailable[1L to MARINE_SOURCE] = now.minusSeconds(1799)
        val coordinator = RefreshCoordinator(store, this, clock)
        repeat(2) {
            assertEquals(RefreshResult.Unavailable,
                coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Marine)).single().result)
        }
        assertTrue(store.calls.isEmpty())
        assertEquals(RefreshResult.Updated,
            coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Marine, force = true)).single().result)
        store.cached.clear()
        store.unavailable[1L to MARINE_SOURCE] = now.minusSeconds(1800)
        assertEquals(RefreshResult.Updated,
            coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Marine)).single().result)
        assertEquals(listOf(MARINE_SOURCE, MARINE_SOURCE), store.calls.map { it.sourceId })
    }

    @Test fun unexpectedFetchFailureIsScopedAndDoesNotCancelSharedCallers() = runTest {
        val store = FakeStore(now, 2)
        val gate = CompletableDeferred<Unit>()
        store.gate = { gate.await(); throw IllegalStateException("Storage unavailable") }
        val logged = mutableListOf<Exception>()
        val coordinator = RefreshCoordinator(store, this, clock, logged::add)
        val first = async { coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Marine, true)) }
        runCurrent()
        val second = async { coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Marine, true)) }
        runCurrent()
        gate.complete(Unit)
        val outcome = first.await().single()
        assertEquals(outcome, second.await().single())
        assertEquals(RefreshResult.Failed, outcome.result)
        assertTrue(outcome.unexpectedFailure)
        assertTrue(outcome.retryable)
        assertEquals(1, logged.size)
        val activity = coordinator.activity.value
        assertTrue(activity.scopes.isEmpty())
        assertTrue(activity.sources.isEmpty())
        assertNotNull(activity.failure(store.locations.getValue(1), ContentScope.Marine))
        assertNull(activity.failure(store.locations.getValue(1), ContentScope.Forecast))
        assertNull(activity.failure(store.locations.getValue(2), ContentScope.Marine))
        store.gate = {}
        assertTrue(coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Marine, true)).single().usable)
        assertEquals(RefreshActivity(), coordinator.activity.value)
    }

    @Test fun failedLocationLookupAndCacheLookupReturnFeedbackAndRecover() = runTest {
        val store = FakeStore(now)
        val logged = mutableListOf<Exception>()
        val coordinator = RefreshCoordinator(store, this, clock, logged::add)
        store.locationGate = { throw IllegalStateException("Read failed") }
        assertTrue(coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Marine)).single().unexpectedFailure)
        assertNotNull(coordinator.activity.value.failure(store.locations.getValue(1), ContentScope.Marine))
        store.locationGate = {}
        store.cacheGate = { throw IllegalStateException("Cache read failed") }
        assertTrue(coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Marine)).single().unexpectedFailure)
        assertEquals(2, logged.size)
        assertTrue(store.calls.isEmpty())
        store.cacheGate = {}
        assertTrue(coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Marine)).single().usable)
        assertEquals(RefreshActivity(), coordinator.activity.value)
    }

    @Test fun unexpectedPreferredFailureDoesNotMarkSuccessfulFallbackAsFailed() = runTest {
        val store = FakeStore(now)
        store.gate = { if (it.sourceId == "preferred") throw IllegalStateException("Commit failed") }
        val coordinator = RefreshCoordinator(store, this, clock)
        val outcomes = coordinator.refresh(RefreshRequest(listOf(1), ContentScope.Forecast, true))
        assertTrue(outcomes.first().unexpectedFailure)
        assertTrue(outcomes.last().usable)
        assertEquals(RefreshActivity(), coordinator.activity.value)
    }

    @Test fun backgroundRetryBudgetIsThreeAttempts() {
        assertTrue(shouldRetryRefresh(true, 0))
        assertTrue(shouldRetryRefresh(true, 1))
        assertFalse(shouldRetryRefresh(true, 2))
        assertFalse(shouldRetryRefresh(false, 0))
    }
}

private class FakeStore(private val now: Instant, count: Int = 1) : RefreshStore {
    val locations = (1..count).associate { id -> id.toLong() to LocationEntity(id.toLong(), "Place", null, null, null, 0.0, 0.0, "UTC", id, null) }.toMutableMap()
    val cached = mutableMapOf<Pair<Long, String>, Instant>()
    val unavailable = mutableMapOf<Pair<Long, String>, Instant>()
    val calls = mutableListOf<SourceKey>()
    val failures = mutableSetOf<String>()
    var gate: suspend (SourceKey) -> Unit = {}
    var cacheGate: suspend () -> Unit = {}
    var locationGate: suspend () -> Unit = {}
    var active = 0
    var peak = 0
    var cancelled = 0

    override suspend fun location(id: Long): LocationEntity? { locationGate(); return locations[id] }
    override suspend fun unavailableAt(location: LocationEntity, source: String) = unavailable[location.id to source]
    override suspend fun sources(location: LocationEntity, scope: ContentScope) =
        if (scope == ContentScope.Marine) listOf(MARINE_SOURCE) else listOf("preferred", "fallback")
    override suspend fun cachedAt(location: LocationEntity, source: String): Instant? {
        cacheGate()
        return cached[location.id to source]
    }
    override suspend fun fetch(location: LocationEntity, source: String): SourceOutcome {
        val key = SourceKey(location.id, location.revision, source)
        calls += key
        active++
        peak = maxOf(peak, active)
        try {
            gate(key)
            if (locations[location.id]?.revision != location.revision) return SourceOutcome(location.id, source, RefreshResult.Discarded)
            if (source in failures) return SourceOutcome(location.id, source, RefreshResult.Failed, retryable = true)
            cached[location.id to source] = now
            return SourceOutcome(location.id, source, RefreshResult.Updated)
        } catch (error: CancellationException) {
            cancelled++
            throw error
        } finally { active-- }
    }
}
