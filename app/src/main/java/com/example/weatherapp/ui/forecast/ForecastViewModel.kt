package com.example.weatherapp.ui.forecast

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.weatherapp.data.repository.*
import com.example.weatherapp.domain.model.ProviderForecast
import com.example.weatherapp.domain.model.MarineConditions
import com.example.weatherapp.settings.WeatherSettings
import com.example.weatherapp.settings.WeatherSettingsRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class AppPage(val title: String) { Forecasts("Forecasts"), Locations("Locations") }

data class NavigationState(
    val page: AppPage = AppPage.Forecasts,
    val tab: ContentScope = ContentScope.Forecast,
    val locationId: Long? = null,
    val consumedEvent: String? = null
) {
    fun widgetEvent(eventId: String, requestedId: Long?, chooseLocations: Boolean, ids: List<Long>): NavigationState {
        if (eventId == consumedEvent) return this
        return copy(
            page = if (chooseLocations || ids.isEmpty()) AppPage.Locations else AppPage.Forecasts,
            tab = ContentScope.Forecast,
            locationId = requestedId?.takeIf { it in ids } ?: ids.firstOrNull(),
            consumedEvent = eventId
        )
    }
}

data class ForecastUiState(
    val items: List<LocationForecast> = emptyList(),
    val locationsLoaded: Boolean = false,
    val navigation: NavigationState = NavigationState(),
    val refreshActivity: RefreshActivity = RefreshActivity(),
    val expandedDay: LocalDate? = null,
    val comparisonDayOffset: Int = 0,
    val providerOptions: Map<Long, List<ProviderOption>> = emptyMap(),
    val settings: WeatherSettings = WeatherSettings(),
    val loadError: String? = null,
    val sourceErrors: Map<Long, String> = emptyMap(),
    val widgetErrors: Map<Long, String> = emptyMap(),
    val now: Instant = Instant.now()
) {
    val selected: LocationForecast?
        get() = items.firstOrNull { it.location.id == navigation.locationId } ?: items.firstOrNull()
    val selectedForecast: ProviderForecast? get() = selected?.forecast
    val marineConditions: MarineConditions? get() = selected?.marineConditions
    val isRefreshing: Boolean get() = selected?.let { refreshActivity.isActive(it.location, navigation.tab) } == true
    val refreshProblem: String? get() = selected?.let { refreshActivity.failure(it.location, navigation.tab) }
}

class ForecastViewModel(
    private val locationRepository: LocationRepository,
    private val weatherRepository: WeatherRepository,
    private val settingsRepository: WeatherSettingsRepository,
    private val coordinator: RefreshCoordinator,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val clock: Clock = Clock.systemUTC()
) : ViewModel() {
    private val _state = mutableStateOf(ForecastUiState(
        navigation = NavigationState(
            page = savedState.get<String>("page")?.let(AppPage::valueOf) ?: AppPage.Forecasts,
            tab = savedState.get<String>("tab")?.let(ContentScope::valueOf) ?: ContentScope.Forecast,
            locationId = savedState["location"],
            consumedEvent = savedState["consumedEvent"]
        ),
        now = clock.instant()
    ))
    val state: State<ForecastUiState> = _state

    init {
        viewModelScope.launch {
            weatherRepository.observeLocationForecasts().retryWhen { error, attempt ->
                if (error is CancellationException) throw error
                android.util.Log.e("ForecastViewModel", "Could not read saved forecasts", error)
                _state.value = _state.value.copy(loadError = "Unable to load saved forecasts. Retrying…")
                delay(((attempt + 1) * 1000).coerceAtMost(30_000))
                true
            }.collect { items ->
                val old = _state.value.selected?.location
                val nav = _state.value.navigation
                val selected = nav.locationId?.takeIf { id -> items.any { it.location.id == id } }
                    ?: items.firstOrNull()?.location?.id
                val options = items.associate { it.location.id to weatherRepository.providerOptions(it.location) }
                _state.value = _state.value.copy(items = items, locationsLoaded = true, providerOptions = options, loadError = null)
                navigate(nav.copy(locationId = selected, page = if (items.isEmpty()) AppPage.Locations else nav.page))
                consumePendingWidgetNavigation()
                val current = _state.value.selected?.location
                if (old?.id != current?.id || old?.revision != current?.revision) checkVisible()
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.catch { error ->
                if (error is CancellationException) throw error
                android.util.Log.e("ForecastViewModel", "Could not read weather settings", error)
            }.collect { _state.value = _state.value.copy(settings = it) }
        }
        viewModelScope.launch {
            coordinator.activity.collect { _state.value = _state.value.copy(refreshActivity = it) }
        }
    }

    private fun navigate(nav: NavigationState) {
        _state.value = _state.value.copy(navigation = nav)
        savedState["page"] = nav.page.name
        savedState["tab"] = nav.tab.name
        savedState["location"] = nav.locationId
        savedState["consumedEvent"] = nav.consumedEvent
    }

    fun widgetNavigation(event: String, locationId: Long?, chooseLocations: Boolean) {
        if (event == _state.value.navigation.consumedEvent) return
        savedState["pendingEvent"] = event
        savedState["pendingLocation"] = locationId
        savedState["pendingLocationsPage"] = chooseLocations
        if (!_state.value.locationsLoaded) return
        consumePendingWidgetNavigation()
        checkVisible()
    }

    private fun consumePendingWidgetNavigation() {
        val event = savedState.get<String>("pendingEvent") ?: return
        val locationId = savedState.get<Long>("pendingLocation")
        val chooseLocations = savedState.get<Boolean>("pendingLocationsPage") ?: false
        val nav = _state.value.navigation.widgetEvent(event, locationId, chooseLocations, _state.value.items.map { it.location.id })
        navigate(nav)
        _state.value = _state.value.copy(expandedDay = null)
        savedState.remove<String>("pendingEvent")
        savedState.remove<Long>("pendingLocation")
        savedState.remove<Boolean>("pendingLocationsPage")
    }

    fun selectLocation(locationId: Long) {
        navigate(_state.value.navigation.copy(locationId = locationId))
        _state.value = _state.value.copy(expandedDay = null)
        checkVisible()
    }

    fun selectTab(tab: ContentScope) {
        navigate(_state.value.navigation.copy(tab = tab))
        checkVisible()
    }

    fun selectPage(page: AppPage) {
        navigate(_state.value.navigation.copy(page = page))
        checkVisible()
    }

    fun onResume() {
        updateTime()
        checkVisible()
    }

    fun updateTime() { _state.value = _state.value.copy(now = clock.instant()) }

    fun selectComparisonDay(offset: Int) {
        _state.value = _state.value.copy(comparisonDayOffset = offset)
        checkVisible()
    }

    fun checkVisible() = refreshVisible(false)
    fun refreshSelected() = refreshVisible(true)

    private fun refreshVisible(force: Boolean) {
        val snapshot = _state.value
        if (!snapshot.locationsLoaded || snapshot.navigation.page != AppPage.Forecasts) return
        val location = snapshot.selected?.location ?: return
        viewModelScope.launch {
            coordinator.refresh(RefreshRequest(listOf(location.id), snapshot.navigation.tab, force))
        }
    }

    fun toggleExpandedDay(date: LocalDate) {
        _state.value = _state.value.copy(expandedDay = if (_state.value.expandedDay == date) null else date)
    }

    fun setWidgetLocation(locationId: Long, widgetOrder: Int) {
        locationAction(locationId, widget = true) { locationRepository.setWidgetLocation(locationId, widgetOrder) }
    }

    fun setForecastSource(locationId: Long, providerId: String) {
        locationAction(locationId, widget = false) {
            weatherRepository.setForecastSource(locationId, providerId)
            coordinator.refresh(RefreshRequest(listOf(locationId), ContentScope.Forecast))
        }
    }

    private fun locationAction(locationId: Long, widget: Boolean, action: suspend () -> Unit) {
        fun setError(message: String?) {
            val errors = (if (widget) _state.value.widgetErrors else _state.value.sourceErrors).toMutableMap()
            if (message == null) errors.remove(locationId) else errors[locationId] = message
            _state.value = if (widget) _state.value.copy(widgetErrors = errors) else _state.value.copy(sourceErrors = errors)
        }
        setError(null)
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("ForecastViewModel", "Could not save location preference", error)
                setError(if (widget) "Could not change widget location. Please try again." else "Could not change source. Please try again.")
            }
        }
    }
}

fun updateStatusLabel(fetchedAt: Instant?, failed: Boolean, active: Boolean, zone: java.time.ZoneId): String {
    if (active) return if (fetchedAt == null) "Loading…" else "Updating…"
    val time = fetchedAt?.atZone(zone)?.format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
    return when {
        failed && time != null -> "Update failed · showing data from $time"
        failed -> "Update failed · no forecast available"
        time != null -> "Updated $time"
        else -> "Forecast unavailable"
    }
}
