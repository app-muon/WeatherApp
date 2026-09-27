package com.example.weatherapp

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.weatherapp.data.api.*
import com.example.weatherapp.data.db.*
import com.example.weatherapp.data.provider.*
import com.example.weatherapp.data.repository.*
import com.example.weatherapp.domain.mapper.*
import com.example.weatherapp.domain.model.*
import com.example.weatherapp.settings.WeatherSettingsRepository
import com.example.weatherapp.ui.forecast.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.*

@RunWith(AndroidJUnit4::class)
class WeatherRepositoryTest {
    private lateinit var db: WeatherDatabase
    private lateinit var repository: WeatherRepository
    private lateinit var locations: LocationRepository
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: RefreshCoordinator
    private lateinit var settings: WeatherSettingsRepository
    private val now = Instant.parse("2026-09-27T12:00:00Z")
    private val provider = FakeProvider()
    private val fallback = FakeProvider(WeatherProviderIds.MET_NORWAY)
    private val marine = FakeMarine()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settings = WeatherSettingsRepository(context)
        repository = WeatherRepository(db, marine, settings, listOf(provider, fallback),
            ForecastParser(), MarineParser(), ProviderForecastJsonCodec(), Clock.fixed(now, ZoneOffset.UTC))
        locations = LocationRepository(db, object : GeocodingApiClient {
            override suspend fun searchLocations(name: String, count: Int, language: String, format: String) = GeocodingResponse(emptyList())
        }, { WeatherProviderIds.OPEN_METEO })
        coordinator = RefreshCoordinator(repository, scope, Clock.fixed(now, ZoneOffset.UTC))
    }

    @After fun teardown() { scope.cancel(); db.close() }

    @Test fun replacementWhileFetchingCannotWriteOldPlaceEvenWhenItFinishesLast() = runBlocking {
        val id = locations.saveLocation(null, place("Old", 10.0))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        provider.handler = { location ->
            if (location.revision == 0L) { started.complete(Unit); release.await() }
            response(location, provider.id)
        }
        val old = async { coordinator.refresh(RefreshRequest(listOf(id), ContentScope.Forecast, true)) }
        withTimeout(5000) { started.await() }
        locations.saveLocation(id, place("New", 30.0))
        val current = coordinator.refresh(RefreshRequest(listOf(id), ContentScope.Forecast, true))
        assertEquals(RefreshResult.Updated, current.single().result)
        release.complete(Unit)
        assertEquals(RefreshResult.Discarded, old.await().single().result)
        val item = repository.observeLocationForecasts().first().single()
        assertEquals("New", item.location.name)
        assertEquals(1L, item.location.revision)
        assertEquals(30.0, item.forecast!!.daily.single().tempMax!!, 0.0)
        assertEquals(1, item.providerStatuses.size)
        assertNull(item.providerStatuses.single().lastError)
    }

    @Test fun deletingDuringRequestDiscardsFailureStatus() = runBlocking {
        val id = locations.saveLocation(null, place("Delete"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        provider.handler = { started.complete(Unit); release.await(); throw IOException() }
        val request = async { coordinator.refresh(RefreshRequest(listOf(id), ContentScope.Forecast, true)) }
        withTimeout(5000) { started.await() }
        locations.deleteLocation(id)
        release.complete(Unit)
        assertEquals(RefreshResult.Discarded, request.await().single().result)
        assertNull(db.forecastCacheDao().get(id, provider.id))
        assertNull(db.providerStatusDao().get(id, provider.id))
    }

    @Test fun malformedWeatherAndMarineFailuresRetainSuccessfulCaches() = runBlocking {
        val id = locations.saveLocation(null, place("Cache"))
        val location = db.locationDao().getById(id)!!
        repository.fetch(location, provider.id)
        val weatherBefore = db.forecastCacheDao().get(id, provider.id)
        provider.handler = { ProviderFetchResult(ProviderForecast(provider.id, "Fake", id, now, null, emptyList(), emptyList(), null), "{}") }
        assertEquals(RefreshResult.Failed, repository.fetch(location, provider.id).result)
        assertEquals(weatherBefore, db.forecastCacheDao().get(id, provider.id))
        assertNotNull(db.providerStatusDao().get(id, provider.id)?.lastError)
        assertEquals(RefreshResult.Updated, repository.fetch(location, MARINE_SOURCE).result)
        val marineBefore = db.marineCacheDao().get(id)
        marine.error = IOException()
        assertEquals(RefreshResult.Failed, repository.fetch(location, MARINE_SOURCE).result)
        assertEquals(marineBefore, db.marineCacheDao().get(id))
        assertNotNull(db.marineStatusDao().get(id)?.lastError)
        marine.error = null
        marine.empty = true
        assertEquals(RefreshResult.Failed, repository.fetch(location, MARINE_SOURCE).result)
        assertEquals(marineBefore, db.marineCacheDao().get(id))
    }

    @Test fun unavailableMarineIsCachedSeparatelyAndRetainsLastSuccessfulData() = runBlocking {
        val id = locations.saveLocation(null, place("Inland"))
        val request = RefreshRequest(listOf(id), ContentScope.Marine)
        marine.noData = true
        val unavailable = coordinator.refresh(request).single()
        assertEquals(RefreshResult.Unavailable, unavailable.result)
        assertFalse(unavailable.retryable)
        var item = repository.observeLocationForecasts().first().single()
        assertTrue(item.marineUnavailable)
        assertNull(item.marineStatus?.lastError)
        assertNull(item.marineConditions)
        repeat(2) { assertEquals(RefreshResult.Unavailable, coordinator.refresh(request).single().result) }
        assertEquals(1, marine.calls)
        marine.noData = false
        assertEquals(RefreshResult.Updated, coordinator.refresh(request.copy(force = true)).single().result)
        val cache = db.marineCacheDao().get(id)
        assertNotNull(cache)
        marine.noData = true
        assertEquals(RefreshResult.Unavailable, coordinator.refresh(request.copy(force = true)).single().result)
        assertEquals(cache, db.marineCacheDao().get(id))
        item = repository.observeLocationForecasts().first().single()
        assertTrue(item.marineUnavailable)
        assertNotNull(item.marineConditions)
        assertEquals(now, item.marineFetchedAt)
        assertNull(item.marineStatus?.lastError)
        marine.noData = false
        coordinator.refresh(request.copy(force = true))
        assertFalse(repository.observeLocationForecasts().first().single().marineUnavailable)
    }

    @Test fun cancellationIsNotRecordedAsARefreshFailure() = runBlocking {
        val id = locations.saveLocation(null, place("Cancel"))
        val location = db.locationDao().getById(id)!!
        repository.fetch(location, provider.id)
        val before = db.forecastCacheDao().get(id, provider.id)
        val started = CompletableDeferred<Unit>()
        provider.handler = { started.complete(Unit); awaitCancellation() }
        val request = launch { coordinator.refresh(RefreshRequest(listOf(id), ContentScope.Forecast, true)) }
        withTimeout(5000) { started.await() }
        request.cancelAndJoin()
        assertEquals(before, db.forecastCacheDao().get(id, provider.id))
        assertNull(db.providerStatusDao().get(id, provider.id)?.lastError)
    }

    @Test fun widgetSlotsSwapAndNewLocationsFillLowestGap() = runBlocking {
        val first = locations.saveLocation(null, place("First"))
        val second = locations.saveLocation(null, place("Second"))
        locations.setWidgetLocation(second, 0)
        assertEquals(1, db.locationDao().getById(first)?.widgetOrder)
        assertEquals(0, db.locationDao().getById(second)?.widgetOrder)
        locations.deleteLocation(second)
        val third = locations.saveLocation(null, place("Third"))
        assertEquals(0, db.locationDao().getById(third)?.widgetOrder)
        assertEquals(listOf(third, first), db.locationDao().getWidgetLocations().map { it.id })
    }

    @Test fun activeWidgetObserverSeesSourceStatusAndLocationChanges() = runBlocking {
        val id = locations.saveLocation(null, place("Observed"))
        val emissions = Channel<List<LocationForecast>>(Channel.UNLIMITED)
        val observing = launch { repository.observeWidgetForecasts().collect { emissions.send(it) } }
        suspend fun awaitItem(predicate: (LocationForecast) -> Boolean): LocationForecast = withTimeout(5000) {
            while (true) {
                val item = emissions.receive().firstOrNull()
                if (item != null && predicate(item)) return@withTimeout item
            }
            error("unreachable")
        }
        awaitItem { it.location.id == id }
        coordinator.refresh(RefreshRequest(listOf(id), ContentScope.Compare, true))
        awaitItem { it.providerForecasts.size == 2 }
        repository.setForecastSource(id, fallback.id)
        awaitItem { it.forecast?.providerId == fallback.id }
        fallback.handler = { throw IOException() }
        repository.fetch(db.locationDao().getById(id)!!, fallback.id)
        awaitItem { !it.lastRefreshFailed && it.usingFallback && it.preferredSourceError != null }
        locations.saveLocation(id, place("Replaced"))
        awaitItem { it.location.name == "Replaced" && it.forecast == null && it.providerStatuses.isEmpty() }
        observing.cancelAndJoin()
    }

    @Test fun permanentFailuresSkipBackgroundRetriesButExplicitRefreshAttemptsAgain() = runBlocking {
        val id = locations.saveLocation(null, place("Config"))
        var calls = 0
        provider.handler = { calls++; throw ProviderConfigurationException() }
        coordinator.refresh(RefreshRequest(listOf(id), ContentScope.Forecast))
        assertEquals(1, calls)
        coordinator.refresh(RefreshRequest(listOf(id), ContentScope.Forecast, skipPermanentFailures = true))
        assertEquals(1, calls)
        coordinator.refresh(RefreshRequest(listOf(id), ContentScope.Forecast, force = true))
        assertEquals(2, calls)
    }

    @Test fun viewModelUsesVisibleTabOnResumeAndPreservesNavigation() = runBlocking {
        val id = locations.saveLocation(null, place("Navigation"))
        val saved = SavedStateHandle()
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            ForecastViewModel(locations, repository, settings, coordinator, saved, Clock.fixed(now, ZoneOffset.UTC)).also { store.put("forecast", it) }
        }
        withTimeout(5000) { while (!vm.state.value.locationsLoaded || vm.state.value.selectedForecast == null) delay(20) }
        withContext(Dispatchers.Main) {
            vm.selectPage(AppPage.Locations)
            vm.widgetNavigation("tap1", id, false)
            vm.selectTab(ContentScope.Marine)
        }
        withTimeout(5000) { while (vm.state.value.marineConditions == null) delay(20) }
        val weatherCalls = provider.calls
        withContext(Dispatchers.Main) { vm.onResume() }
        delay(100)
        assertEquals(weatherCalls, provider.calls)
        assertEquals(1, marine.calls)
        withContext(Dispatchers.Main) {
            vm.widgetNavigation("tap1", id, false)
            assertEquals(ContentScope.Marine, vm.state.value.navigation.tab)
            vm.widgetNavigation("tap2", id, false)
            assertEquals(ContentScope.Forecast, vm.state.value.navigation.tab)
            vm.selectTab(ContentScope.Compare)
        }
        withTimeout(5000) { while (vm.state.value.selected!!.providerForecasts.size < 2) delay(20) }
        withContext(Dispatchers.Main) { store.clear() }
        val restored = withContext(Dispatchers.Main) {
            ForecastViewModel(locations, repository, settings, coordinator, saved, Clock.fixed(now, ZoneOffset.UTC)).also { store.put("forecast", it) }
        }
        assertEquals(ContentScope.Compare, restored.state.value.navigation.tab)
        assertEquals("tap2", restored.state.value.navigation.consumedEvent)
        withContext(Dispatchers.Main) { store.clear() }
    }

    @Test fun widgetNavigationWaitsForInitialRoomQueryAndCanOpenEmptyLocations() = runBlocking {
        val first = locations.saveLocation(null, place("First"))
        val target = locations.saveLocation(null, place("Target"))
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            ForecastViewModel(locations, repository, settings, coordinator).also {
                store.put("forecast", it)
                it.widgetNavigation("cold-tap", target, false)
            }
        }
        withTimeout(5000) { while (!vm.state.value.locationsLoaded) delay(20) }
        assertEquals(target, vm.state.value.navigation.locationId)
        assertEquals("cold-tap", vm.state.value.navigation.consumedEvent)
        locations.deleteLocation(first)
        locations.deleteLocation(target)
        withTimeout(5000) { while (vm.state.value.items.isNotEmpty()) delay(20) }
        withContext(Dispatchers.Main) { vm.widgetNavigation("empty-tap", target, false) }
        assertEquals(AppPage.Locations, vm.state.value.navigation.page)
        withContext(Dispatchers.Main) { store.clear() }
    }

    private fun place(name: String, latitude: Double = 20.0) = GeocodingResult(null, name, latitude, 0.0, null, null, null, "UTC")

    private inner class FakeProvider(override val id: String = WeatherProviderIds.OPEN_METEO) : WeatherProvider {
        override val displayName = id
        override val shortName = id
        var calls = 0
        var handler: suspend (LocationEntity) -> ProviderFetchResult = { response(it, id) }
        override suspend fun isAvailableFor(location: LocationEntity) = true
        override suspend fun fetchForecast(location: LocationEntity, units: WeatherUnits): ProviderFetchResult {
            calls++
            return handler(location)
        }
    }

    private fun response(location: LocationEntity, source: String) = ProviderFetchResult(
        ProviderForecast(source, source, location.id, now, null, emptyList(),
            listOf(DailyForecast(LocalDate.parse("2026-09-27"), 2, 10.0, location.latitude, null, null, null, null, null, null, null, null)), null), "{}"
    )

    private class FakeMarine : MarineApiClient {
        var calls = 0
        var error: Exception? = null
        var empty = false
        var noData = false
        override suspend fun marine(latitude: Double, longitude: Double, hourly: String, timezone: String): JsonObject {
            calls++
            error?.let { throw it }
            return JsonParser.parseString(if (empty) """{"timezone":"UTC","hourly":{"time":[]}}"""
                else if (noData) """{"timezone":"UTC","hourly":{"time":["2026-09-27T12:00"],"wave_height":[null]}}"""
                else """{"timezone":"UTC","hourly":{"time":["2026-09-27T12:00"],"wave_height":[1.5]}}""").asJsonObject
        }
    }
}
