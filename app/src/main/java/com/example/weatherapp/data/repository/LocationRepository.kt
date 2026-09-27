package com.example.weatherapp.data.repository

import androidx.room.withTransaction
import com.example.weatherapp.data.api.GeocodingApiClient
import com.example.weatherapp.data.api.GeocodingResult
import com.example.weatherapp.data.db.ForecastSourcePreferenceEntity
import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.data.db.WeatherDatabase
import com.example.weatherapp.data.db.WidgetSourcePreferenceEntity
import kotlinx.coroutines.flow.Flow

class LocationRepository(
    private val database: WeatherDatabase,
    private val geocodingApi: GeocodingApiClient,
    private val defaultSource: (LocationEntity) -> String
) {
    private val locationDao = database.locationDao()
    fun observeLocations(): Flow<List<LocationEntity>> = locationDao.observeLocations()

    suspend fun search(query: String): List<GeocodingResult> {
        if (query.isBlank()) return emptyList()
        return geocodingApi.searchLocations(query.trim()).results.orEmpty()
    }

    suspend fun saveLocation(targetLocationId: Long?, result: GeocodingResult): Long {
        val id = database.withTransaction {
            val existing = targetLocationId?.let { locationDao.getById(it) }
            require(targetLocationId == null || existing != null) { "Location was deleted. Add it as a new location." }
            val occupied = locationDao.getWidgetLocations().mapNotNull { it.widgetOrder }
            val location = LocationEntity(
                id = existing?.id ?: 0,
                name = result.name, country = result.country, countryCode = result.countryCode,
                adminArea = result.admin1, latitude = result.latitude, longitude = result.longitude,
                timezone = result.timezone,
                displayOrder = existing?.displayOrder ?: (locationDao.getMaxDisplayOrder() + 1),
                widgetOrder = if (existing != null) existing.widgetOrder else (0..1).firstOrNull { it !in occupied },
                revision = (existing?.revision ?: -1) + 1
            )
            val savedId = if (existing == null) locationDao.upsert(location) else {
                locationDao.update(location)
                existing.id
            }
            database.forecastCacheDao().deleteForLocation(savedId)
            database.marineCacheDao().deleteForLocation(savedId)
            database.providerStatusDao().deleteForLocation(savedId)
            database.marineStatusDao().deleteForLocation(savedId)
            val source = defaultSource(location)
            database.forecastSourcePreferenceDao().upsert(ForecastSourcePreferenceEntity(savedId, source))
            database.widgetSourcePreferenceDao().upsert(WidgetSourcePreferenceEntity(savedId, source))
            savedId
        }
        return id
    }

    suspend fun setWidgetLocation(locationId: Long, widgetOrder: Int) {
        require(widgetOrder in 0..1) { "Widget order must be 0 or 1" }
        database.withTransaction {
            val selected = locationDao.getById(locationId) ?: return@withTransaction
            if (selected.widgetOrder == widgetOrder) return@withTransaction
            val displaced = locationDao.getWidgetLocations().find { it.widgetOrder == widgetOrder }
            locationDao.clearWidgetOrderForLocation(locationId)
            locationDao.clearWidgetOrder(widgetOrder)
            if (selected.widgetOrder != null && displaced != null) {
                locationDao.setWidgetOrder(displaced.id, selected.widgetOrder)
            }
            locationDao.setWidgetOrder(locationId, widgetOrder)
        }
    }

    suspend fun deleteLocation(locationId: Long) {
        database.withTransaction { locationDao.deleteById(locationId) }
    }
}
