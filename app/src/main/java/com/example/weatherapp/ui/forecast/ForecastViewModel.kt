package com.example.weatherapp.ui.forecast

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.weatherapp.data.repository.LocationRepository
import com.example.weatherapp.data.repository.LocationForecast
import com.example.weatherapp.data.repository.ProviderOption
import com.example.weatherapp.data.repository.WeatherRepository
import com.example.weatherapp.settings.WeatherSettings
import com.example.weatherapp.settings.WeatherSettingsRepository
import com.example.weatherapp.domain.model.MarineConditions
import com.example.weatherapp.domain.model.ProviderForecast
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ForecastUiState(
    val items: List<LocationForecast> = emptyList(),
    val selectedLocationId: Long? = null,
    val isRefreshing: Boolean = false,
    val refreshMessage: String? = null,
    val expandedDay: LocalDate? = null,
    val comparisonDayOffset: Int = 0,
    val comparisonLoadedLocationIds: Set<Long> = emptySet(),
    val providerOptions: Map<Long, List<ProviderOption>> = emptyMap(),
    val settings: WeatherSettings = WeatherSettings()
) {
    val selected: LocationForecast?
        get() = items.firstOrNull { it.location.id == selectedLocationId } ?: items.firstOrNull()
    val selectedForecast: ProviderForecast?
        get() = selected?.forecast
    val marineConditions: MarineConditions?
        get() = selected?.marineConditions
}

class ForecastViewModel(
    private val locationRepository: LocationRepository,
    private val weatherRepository: WeatherRepository,
    private val settingsRepository: WeatherSettingsRepository
) : ViewModel() {
    private val _state = mutableStateOf(ForecastUiState())
    val state: State<ForecastUiState> = _state

    init {
        viewModelScope.launch {
            weatherRepository.observeLocationForecasts().collect { items ->
                val selected = _state.value.selectedLocationId?.takeIf { id -> items.any { it.location.id == id } }
                    ?: items.firstOrNull()?.location?.id
                val options = items.associate { item ->
                    item.location.id to weatherRepository.providerOptions(item.location)
                }
                _state.value = _state.value.copy(
                    items = items,
                    selectedLocationId = selected,
                    providerOptions = options
                )
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _state.value = _state.value.copy(settings = settings)
            }
        }
        viewModelScope.launch {
            refreshAll()
        }
    }

    fun selectLocation(locationId: Long) {
        _state.value = _state.value.copy(selectedLocationId = locationId, expandedDay = null)
    }

    fun selectComparisonDay(offset: Int) {
        _state.value = _state.value.copy(comparisonDayOffset = offset)
        if (offset == 0 || offset == 1 || offset == 2 || offset == 7 || offset == -1) {
            refreshComparisonForSelected()
        }
    }

    fun refreshComparisonForSelected() {
        viewModelScope.launch {
            val selected = _state.value.selected?.location ?: return@launch
            if (selected.id in _state.value.comparisonLoadedLocationIds) return@launch
            _state.value = _state.value.copy(isRefreshing = true, refreshMessage = null)
            val result = weatherRepository.refreshLocation(selected)
            val loaded = if (result.isSuccess) {
                _state.value.comparisonLoadedLocationIds + selected.id
            } else {
                _state.value.comparisonLoadedLocationIds
            }
            val message = if (result.isFailure && _state.value.selected?.forecast == null) {
                "Could not load forecast. Check your connection."
            } else if (result.isFailure) {
                "Could not refresh forecast"
            } else {
                null
            }
            _state.value = _state.value.copy(
                isRefreshing = false,
                refreshMessage = message,
                comparisonLoadedLocationIds = loaded
            )
        }
    }

    fun refreshSelected() {
        viewModelScope.launch {
            val selected = _state.value.selected?.location ?: return@launch
            _state.value = _state.value.copy(isRefreshing = true, refreshMessage = null)
            val result = weatherRepository.refreshLocation(selected)
            val message = if (result.isFailure) {
                if (_state.value.selected?.forecast != null) {
                    "Could not refresh forecast"
                } else {
                    "Could not load forecast. Check your connection."
                }
            } else {
                null
            }
            _state.value = _state.value.copy(isRefreshing = false, refreshMessage = message)
        }
    }

    fun toggleExpandedDay(date: LocalDate) {
        _state.value = _state.value.copy(
            expandedDay = if (_state.value.expandedDay == date) null else date
        )
    }

    fun setWidgetLocation(locationId: Long, widgetOrder: Int) {
        viewModelScope.launch {
            locationRepository.setWidgetLocation(locationId, widgetOrder)
        }
    }

    fun setForecastSource(locationId: Long, providerId: String) {
        viewModelScope.launch {
            weatherRepository.setForecastSource(locationId, providerId)
        }
    }

    private suspend fun refreshAll(defaultOnly: Boolean = false) {
        _state.value = _state.value.copy(isRefreshing = true, refreshMessage = null)
        val result = if (defaultOnly) weatherRepository.refreshAllDefaultOnly() else weatherRepository.refreshAll()
        val message = if (result.isFailure && _state.value.items.any { it.forecast != null }) {
            "Could not refresh forecast"
        } else if (result.isFailure) {
            "Could not load forecast. Check your connection."
        } else {
            null
        }
        _state.value = _state.value.copy(isRefreshing = false, refreshMessage = message)
    }
}
