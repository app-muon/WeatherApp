package com.example.weatherapp.data.repository

import com.example.weatherapp.data.api.GeocodingApiClient
import com.example.weatherapp.data.api.GeocodingResult
import com.example.weatherapp.data.db.ForecastCacheDao
import com.example.weatherapp.data.db.LocationDao
import com.example.weatherapp.data.db.LocationEntity
import kotlinx.coroutines.flow.Flow

class LocationRepository(
    private val locationDao: LocationDao,
    private val forecastCacheDao: ForecastCacheDao,
    private val geocodingApi: GeocodingApiClient
) {
    fun observeLocations(): Flow<List<LocationEntity>> = locationDao.observeLocations()

    suspend fun search(query: String): List<GeocodingResult> {
        if (query.isBlank()) return emptyList()
        return geocodingApi.searchLocations(query.trim()).results.orEmpty()
    }

    suspend fun saveLocation(targetLocationId: Long?, result: GeocodingResult): Long {
        val existing = targetLocationId?.let { locationDao.getById(it) }
        val displayOrder = existing?.displayOrder ?: locationDao.getMaxDisplayOrder() + 1
        val widgetOrder = existing?.widgetOrder ?: locationDao.countWidgetLocations().takeIf { it < 2 }
        val id = locationDao.upsert(
            LocationEntity(
                id = existing?.id ?: 0,
                name = result.name,
                country = result.country,
                adminArea = result.admin1,
                latitude = result.latitude,
                longitude = result.longitude,
                timezone = result.timezone,
                displayOrder = displayOrder,
                widgetOrder = widgetOrder
            )
        )
        val locationId = existing?.id ?: id
        forecastCacheDao.deleteForLocation(locationId)
        return locationId
    }

    suspend fun setWidgetLocation(locationId: Long, widgetOrder: Int) {
        require(widgetOrder in 0..1) { "Widget order must be 0 or 1" }
        locationDao.clearWidgetOrderForLocation(locationId)
        locationDao.clearWidgetOrder(widgetOrder)
        locationDao.setWidgetOrder(locationId, widgetOrder)
    }
}
