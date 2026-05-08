package com.example.weatherapp.ui.forecast

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.weatherapp.data.repository.LocationRepository
import com.example.weatherapp.data.repository.LocationForecast
import com.example.weatherapp.data.repository.WeatherRepository
import com.example.weatherapp.settings.WeatherSettings
import com.example.weatherapp.settings.WeatherSettingsRepository
import com.example.weatherapp.domain.model.MarineConditions
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ForecastUiState(
    val items: List<LocationForecast> = emptyList(),
    val selectedLocationId: Long? = null,
    val isRefreshing: Boolean = false,
    val refreshMessage: String? = null,
    val expandedDay: LocalDate? = null,
    val settings: WeatherSettings = WeatherSettings()
) {
    val selected: LocationForecast?
        get() = items.firstOrNull { it.location.id == selectedLocationId } ?: items.firstOrNull()
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
                _state.value = _state.value.copy(items = items, selectedLocationId = selected)
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

    private suspend fun refreshAll() {
        _state.value = _state.value.copy(isRefreshing = true, refreshMessage = null)
        val result = weatherRepository.refreshAll()
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
