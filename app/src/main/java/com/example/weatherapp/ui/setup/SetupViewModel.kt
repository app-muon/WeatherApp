package com.example.weatherapp.ui.setup

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.weatherapp.data.api.GeocodingResult
import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.data.repository.LocationRepository
import com.example.weatherapp.data.repository.WeatherRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SetupUiState(
    val query: String = "",
    val locations: List<LocationEntity> = emptyList(),
    val targetLocationId: Long? = null,
    val results: List<GeocodingResult> = emptyList(),
    val isSearching: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false
)

class SetupViewModel(
    private val locationRepository: LocationRepository,
    private val weatherRepository: WeatherRepository
) : ViewModel() {
    private val _state = mutableStateOf(SetupUiState())
    val state: State<SetupUiState> = _state
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            locationRepository.observeLocations().collect { locations ->
                val target = _state.value.targetLocationId?.takeIf { id -> locations.any { it.id == id } }
                _state.value = _state.value.copy(locations = locations, targetLocationId = target)
            }
        }
    }

    fun updateQuery(query: String) {
        _state.value = _state.value.copy(query = query, message = null, messageIsError = false)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            search()
        }
    }

    fun selectAddNew() {
        _state.value = _state.value.copy(targetLocationId = null)
    }

    fun selectReplacement(locationId: Long) {
        _state.value = _state.value.copy(targetLocationId = locationId)
    }

    fun searchNow() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search() }
    }

    fun save(result: GeocodingResult) {
        viewModelScope.launch {
            val targetLocationId = _state.value.targetLocationId
            val locationId = locationRepository.saveLocation(targetLocationId, result)
            weatherRepository.initDefaultForecastSource(locationId)
            weatherRepository.refreshLocation(locationId)
            _state.value = _state.value.copy(
                query = "",
                results = emptyList(),
                message = if (targetLocationId == null) "${result.name} added" else "${result.name} replaced",
                messageIsError = false
            )
        }
    }

    fun deleteLocation(locationId: Long) {
        viewModelScope.launch {
            val location = _state.value.locations.firstOrNull { it.id == locationId }
            locationRepository.deleteLocation(locationId)
            _state.value = _state.value.copy(
                targetLocationId = _state.value.targetLocationId?.takeIf { it != locationId },
                message = location?.let { "${it.name} deleted" },
                messageIsError = false
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
            message = when {
                result.isSuccess && result.getOrNull().orEmpty().isEmpty() -> "No locations found"
                result.isFailure -> "Could not search locations. Check your connection."
                else -> null
            },
            messageIsError = result.isFailure || result.getOrNull().orEmpty().isEmpty()
        )
    }
}
