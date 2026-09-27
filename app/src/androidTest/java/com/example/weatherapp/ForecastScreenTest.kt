package com.example.weatherapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.data.db.MarineStatusEntity
import com.example.weatherapp.data.repository.*
import com.example.weatherapp.domain.model.*
import com.example.weatherapp.ui.forecast.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class ForecastScreenTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-09-27T09:20:00Z")
    private val location = LocationEntity(1, "Place", null, null, null, 0.0, 0.0, "UTC", 0, 0)
    private val empty = LocationForecast(location, null, emptyList(), emptyList(), null, null, "open_meteo")

    @Test fun initialLoadingIsNotAnErrorAndRetryIsExplicit() {
        val state = mutableStateOf(ForecastUiState(items = listOf(empty), locationsLoaded = true))
        var retries = 0
        compose.setContent { MaterialTheme { ForecastScreen(state.value, {}, {}, {}, { retries++ }, {}) } }
        compose.onNodeWithText("Loading forecast…").assertExists()
        compose.onNodeWithText("Could not load forecast").assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(items = listOf(empty.copy(
                providerStatuses = listOf(ProviderStatus("open_meteo", 1, now, null, "Failed")), lastRefreshFailed = true)))
        }
        compose.onNodeWithText("Could not load forecast").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test fun retainedDataShowsUpdatingThenFailureTimestamp() {
        val forecast = ProviderForecast("open_meteo", "Open-Meteo", 1, now, null, emptyList(), emptyList(), null)
        val item = empty.copy(forecast = forecast, providerForecasts = listOf(forecast))
        val state = mutableStateOf(ForecastUiState(items = listOf(item), locationsLoaded = true,
            refreshActivity = RefreshActivity(scopes = mapOf(RefreshKey(1, 0, ContentScope.Forecast) to 1))))
        compose.setContent { MaterialTheme { ForecastScreen(state.value, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Updating…").assertExists()
        compose.runOnIdle { state.value = state.value.copy(items = listOf(item.copy(lastRefreshFailed = true)), refreshActivity = RefreshActivity()) }
        compose.onNodeWithText("Update failed · showing data from", substring = true).assertExists()
        compose.onNodeWithText("Open-Meteo").assertExists()
    }

    @Test fun unavailableMarineDoesNotShowNetworkErrorOrRetry() {
        val item = empty.copy(marineStatus = MarineStatusEntity(1, now.toEpochMilli(), null, null, now.toEpochMilli()))
        val state = ForecastUiState(items = listOf(item), locationsLoaded = true,
            navigation = NavigationState(tab = ContentScope.Marine))
        compose.setContent { MaterialTheme { ForecastScreen(state, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("No marine data for this location").assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Update failed", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Loading marine conditions…").assertDoesNotExist()
    }

    @Test fun freshFallbackHasSuccessfulStatusAndSeparatePreferredSourceError() {
        val forecast = ProviderForecast("met_norway", "MET Norway", 1, now, null, emptyList(), emptyList(), null)
        val item = empty.copy(forecast = forecast, providerForecasts = listOf(forecast),
            providerStatuses = listOf(ProviderStatus("open_meteo", 1, now, null, "Source unavailable. Check its configuration.")))
        val state = ForecastUiState(items = listOf(item), locationsLoaded = true, now = now)
        compose.setContent { MaterialTheme { ForecastScreen(state, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Updated", substring = true).assertExists()
        compose.onNodeWithText("Preferred source: Source unavailable. Check its configuration.").assertExists()
        compose.onNodeWithText("Update failed", substring = true).assertDoesNotExist()
    }

    @Test fun marineRendersWithoutAnyWeatherForecast() {
        val item = empty.copy(marineConditions = MarineConditions(now.atZone(ZoneOffset.UTC), 20.0, 1.5, null, null), marineFetchedAt = now)
        val state = ForecastUiState(items = listOf(item), locationsLoaded = true,
            navigation = NavigationState(tab = ContentScope.Marine))
        compose.setContent { MaterialTheme { ForecastScreen(state, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Wave height").assertExists()
        compose.onNodeWithText("1.5 m").assertExists()
        compose.onNodeWithText("Could not load forecast").assertDoesNotExist()
    }
}
