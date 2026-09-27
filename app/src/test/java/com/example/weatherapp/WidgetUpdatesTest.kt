package com.example.weatherapp

import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.data.db.MarineStatusEntity
import com.example.weatherapp.data.repository.*
import com.example.weatherapp.domain.model.ProviderForecast
import com.example.weatherapp.domain.model.ProviderStatus
import com.example.weatherapp.ui.widget.WidgetDisplayState
import com.example.weatherapp.ui.widget.observeWidgetChanges
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetUpdatesTest {
    private val now = Instant.parse("2026-09-27T12:00:00Z")
    private val location = LocationEntity(1, "Place", null, null, null, 0.0, 0.0, "UTC", 0, 0)
    private val forecast = ProviderForecast("preferred", "Preferred", 1, now, null, emptyList(), emptyList(), null)
    private val item = LocationForecast(location, forecast, listOf(forecast), emptyList(), null, null, "preferred")

    @Test fun ignoresMarineOffWidgetAndUnusedCompareSourcesAndAttemptWrites() = runTest {
        val items = MutableStateFlow(listOf(item))
        val activity = MutableStateFlow(RefreshActivity())
        val redraws = mutableListOf<List<WidgetDisplayState>>()
        backgroundScope.launch { observeWidgetChanges(items, activity).toList(redraws) }
        runCurrent()
        assertEquals(1, redraws.size)
        activity.value = RefreshActivity(scopes = mapOf(RefreshKey(2, 0, ContentScope.Forecast) to 1),
            sources = setOf(SourceKey(2, 0, "preferred")))
        runCurrent()
        activity.value = RefreshActivity(scopes = mapOf(RefreshKey(1, 0, ContentScope.Marine) to 1),
            sources = setOf(SourceKey(1, 0, MARINE_SOURCE)))
        runCurrent()
        items.value = listOf(item.copy(marineStatus = MarineStatusEntity(1, now.toEpochMilli(), null, "Failed")))
        runCurrent()
        activity.value = RefreshActivity(scopes = mapOf(RefreshKey(1, 0, ContentScope.Compare) to 1),
            sources = setOf(SourceKey(1, 0, "unused")))
        runCurrent()
        items.value = listOf(item.copy(providerForecasts = listOf(forecast, forecast.copy(providerId = "unused")),
            providerStatuses = listOf(ProviderStatus("preferred", 1, now, now, null))))
        runCurrent()
        activity.value = RefreshActivity()
        runCurrent()
        assertEquals(1, redraws.size)
    }

    @Test fun redrawsForDisplayedSourceActivityForecastFallbackErrorsAndLocations() = runTest {
        val items = MutableStateFlow(listOf(item))
        val activity = MutableStateFlow(RefreshActivity())
        val redraws = mutableListOf<List<WidgetDisplayState>>()
        backgroundScope.launch { observeWidgetChanges(items, activity).toList(redraws) }
        runCurrent()
        activity.value = RefreshActivity(sources = setOf(SourceKey(1, 0, "preferred")))
        runCurrent()
        assertTrue(redraws.last().single().updating)
        activity.value = RefreshActivity()
        runCurrent()
        items.value = listOf(item.copy(forecast = forecast.copy(fetchedAt = now.plusSeconds(60))))
        runCurrent()
        items.value = listOf(item.copy(forecast = forecast.copy(providerId = "fallback"), lastRefreshFailed = false))
        runCurrent()
        assertTrue(redraws.last().single().usingFallback)
        items.value = items.value.map { it.copy(lastRefreshFailed = true) }
        runCurrent()
        activity.value = RefreshActivity(failures = mapOf(RefreshKey(1, 0, ContentScope.Forecast) to "Storage error"))
        runCurrent()
        assertEquals("Storage error", redraws.last().single().refreshProblem)
        items.value = listOf(item.copy(location = location.copy(name = "Replacement", revision = 1)))
        runCurrent()
        assertNull(redraws.last().single().refreshProblem)
        items.value = emptyList()
        runCurrent()
        assertEquals(9, redraws.size)
        assertTrue(redraws.last().isEmpty())
    }
}
