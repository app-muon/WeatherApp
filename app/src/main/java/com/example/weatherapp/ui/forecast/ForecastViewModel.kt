package com.example.weatherapp.ui.forecast

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.weatherapp.data.repository.LocationForecast
import com.example.weatherapp.data.repository.WeatherRepository
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ForecastUiState(
    val items: List<LocationForecast> = emptyList(),
    val selectedLocationId: Long? = null,
    val isRefreshing: Boolean = false,
    val refreshMessage: String? = null,
    val expandedDay: LocalDate? = null
) {
    val selected: LocationForecast?
        get() = items.firstOrNull { it.location.id == selectedLocationId } ?: items.firstOrNull()
}

class ForecastViewModel(
    private val weatherRepository: WeatherRepository
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
                if (_state.value.selected?.forecast != null) "Could not refresh forecast" else result.exceptionOrNull()?.message ?: "Could not load forecast"
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

    private suspend fun refreshAll() {
        _state.value = _state.value.copy(isRefreshing = true, refreshMessage = null)
        val result = weatherRepository.refreshAll()
        val message = if (result.isFailure && _state.value.items.any { it.forecast != null }) {
            "Could not refresh forecast"
        } else {
            result.exceptionOrNull()?.message
        }
        _state.value = _state.value.copy(isRefreshing = false, refreshMessage = message)
    }
}

