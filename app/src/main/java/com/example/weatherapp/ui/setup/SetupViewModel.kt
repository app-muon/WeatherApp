package com.example.weatherapp.ui.setup

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.weatherapp.data.api.GeocodingResult
import com.example.weatherapp.data.repository.LocationRepository
import com.example.weatherapp.data.repository.WeatherRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SetupUiState(
    val query: String = "",
    val selectedSlot: Int = 0,
    val results: List<GeocodingResult> = emptyList(),
    val isSearching: Boolean = false,
    val message: String? = null
)

class SetupViewModel(
    private val locationRepository: LocationRepository,
    private val weatherRepository: WeatherRepository
) : ViewModel() {
    private val _state = mutableStateOf(SetupUiState())
    val state: State<SetupUiState> = _state
    private var searchJob: Job? = null

    fun updateQuery(query: String) {
        _state.value = _state.value.copy(query = query, message = null)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            search()
        }
    }

    fun selectSlot(slot: Int) {
        _state.value = _state.value.copy(selectedSlot = slot)
    }

    fun searchNow() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search() }
    }

    fun save(result: GeocodingResult) {
        viewModelScope.launch {
            val locationId = locationRepository.saveLocation(_state.value.selectedSlot, result)
            weatherRepository.refreshLocation(locationId)
            _state.value = _state.value.copy(
                query = "",
                results = emptyList(),
                message = "${result.name} saved"
            )
        }
    }

    private suspend fun search() {
        val query = _state.value.query
        if (query.isBlank()) {
            _state.value = _state.value.copy(results = emptyList(), isSearching = false)
            return
        }
        _state.value = _state.value.copy(isSearching = true, message = null)
        val result = runCatching { locationRepository.search(query) }
        _state.value = _state.value.copy(
            isSearching = false,
            results = result.getOrDefault(emptyList()),
            message = if (result.isSuccess && result.getOrNull().orEmpty().isEmpty()) "No locations found" else result.exceptionOrNull()?.message
        )
    }
}

