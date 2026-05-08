package com.example.weatherapp.data.repository

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.weatherapp.data.api.WeatherApiClient
import com.example.weatherapp.data.api.MarineApiClient
import com.example.weatherapp.data.db.MarineCacheDao
import com.example.weatherapp.data.db.MarineCacheEntity
import com.example.weatherapp.data.db.ForecastCacheDao
import com.example.weatherapp.data.db.ForecastCacheEntity
import com.example.weatherapp.data.db.LocationDao
import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.domain.mapper.ForecastParser
import com.example.weatherapp.domain.mapper.MarineParser
import com.example.weatherapp.domain.model.Forecast
import com.example.weatherapp.domain.model.MarineConditions
import com.example.weatherapp.settings.WeatherSettingsRepository
import com.example.weatherapp.worker.ForecastRefreshWorker
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

data class LocationForecast(
    val location: LocationEntity,
    val forecast: Forecast?,
    val marineConditions: MarineConditions?,
    val lastRefreshFailed: Boolean = false
)

class WeatherRepository(
    private val locationDao: LocationDao,
    private val forecastCacheDao: ForecastCacheDao,
    private val marineCacheDao: MarineCacheDao,
    private val weatherApi: WeatherApiClient,
    private val marineApi: MarineApiClient,
    private val settingsRepository: WeatherSettingsRepository,
    private val forecastParser: ForecastParser,
    private val marineParser: MarineParser
) {
    private val gson = Gson()

    fun observeLocationForecasts(): Flow<List<LocationForecast>> =
        locationDao.observeLocationsWithCache().map { rows ->
            rows.map { row -> toLocationForecast(row) }
        }

    suspend fun getCachedLocationForecasts(): List<LocationForecast> =
        locationDao.getLocationsWithCache().map { row ->
            toLocationForecast(row)
        }

    suspend fun getCachedWidgetForecasts(): List<LocationForecast> =
        locationDao.getWidgetLocationsWithCache().map { row ->
            toLocationForecast(row)
        }

    suspend fun refreshAll(): Result<Unit> {
        val locations = locationDao.getLocations()
        var failure: Throwable? = null
        locations.forEach { location ->
            val result = refreshLocation(location)
            if (result.isFailure) failure = result.exceptionOrNull()
        }
        return failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    suspend fun refreshLocation(location: LocationEntity): Result<Unit> = runCatching {
        val settings = settingsRepository.settings.first()
        val weatherJson = weatherApi.forecast(
            latitude = location.latitude,
            longitude = location.longitude,
            temperatureUnit = settings.temperatureUnit,
            windSpeedUnit = settings.windSpeedUnit,
            precipitationUnit = settings.precipitationUnit
        )
        val weatherJsonString = gson.toJson(weatherJson)
        forecastCacheDao.upsert(
            ForecastCacheEntity(
                locationId = location.id,
                fetchedAtEpochMillis = Instant.now().toEpochMilli(),
                rawJson = weatherJsonString
            )
        )
        refreshMarine(location, weatherJsonString)
    }

    suspend fun refreshLocation(locationId: Long): Result<Unit> {
        val location = locationDao.getById(locationId)
            ?: return Result.failure(IllegalArgumentException("Location not found"))
        return refreshLocation(location)
    }

    companion object {
        const val STALE_AFTER_MILLIS = 6L * 60L * 60L * 1000L

        fun enqueueRefreshIfStale(workManager: WorkManager, forecasts: List<LocationForecast>) {
            val now = Instant.now().toEpochMilli()
            val stale = forecasts.any { it.forecast == null || now - it.forecast.fetchedAt.toEpochMilli() > STALE_AFTER_MILLIS }
            if (stale) {
                workManager.enqueueUniqueWork(
                    ForecastRefreshWorker.STALE_REFRESH_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<ForecastRefreshWorker>().build()
                )
            }
        }
    }

    private suspend fun refreshMarine(location: LocationEntity, weatherJson: String) {
        val weatherForecast = runCatching {
            forecastParser.parse(location.id, Instant.now(), gson.fromJson(weatherJson, com.google.gson.JsonObject::class.java))
        }.getOrNull() ?: return marineCacheDao.deleteForLocation(location.id)

        val marineJson = runCatching {
            marineApi.marine(
                latitude = location.latitude,
                longitude = location.longitude
            )
        }.getOrNull() ?: return marineCacheDao.deleteForLocation(location.id)

        val marineConditions = runCatching {
            marineParser.parse(marineJson, weatherForecast.current.time)
        }.getOrNull()

        if (marineConditions == null) {
            marineCacheDao.deleteForLocation(location.id)
            return
        }

        marineCacheDao.upsert(
            MarineCacheEntity(
                locationId = location.id,
                fetchedAtEpochMillis = Instant.now().toEpochMilli(),
                rawJson = gson.toJson(marineJson)
            )
        )
    }

    private fun toLocationForecast(row: com.example.weatherapp.data.db.LocationWithCaches): LocationForecast {
        val forecast = row.forecastCache?.let {
            runCatching {
                forecastParser.parse(row.location.id, it.fetchedAtEpochMillis, it.rawJson)
            }.getOrNull()
        }
        val marineConditions = row.marineCache?.let {
            runCatching {
                marineParser.parse(it.rawJson, forecast?.current?.time ?: java.time.Instant.ofEpochMilli(it.fetchedAtEpochMillis).atZone(java.time.ZoneId.systemDefault()))
            }.getOrNull()
        }
        return LocationForecast(
            location = row.location,
            forecast = forecast,
            marineConditions = marineConditions
        )
    }
}
