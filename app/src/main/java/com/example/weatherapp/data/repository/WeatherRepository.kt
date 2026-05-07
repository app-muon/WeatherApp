package com.example.weatherapp.data.repository

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.weatherapp.data.api.WeatherApiClient
import com.example.weatherapp.data.db.ForecastCacheDao
import com.example.weatherapp.data.db.ForecastCacheEntity
import com.example.weatherapp.data.db.LocationDao
import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.domain.mapper.ForecastParser
import com.example.weatherapp.domain.model.Forecast
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
    val lastRefreshFailed: Boolean = false
)

class WeatherRepository(
    private val locationDao: LocationDao,
    private val forecastCacheDao: ForecastCacheDao,
    private val weatherApi: WeatherApiClient,
    private val settingsRepository: WeatherSettingsRepository,
    private val forecastParser: ForecastParser
) {
    private val gson = Gson()

    fun observeLocationForecasts(): Flow<List<LocationForecast>> =
        locationDao.observeLocationsWithCache().map { rows ->
            rows.map { row ->
                LocationForecast(
                    location = row.location,
                    forecast = row.forecastCache?.let {
                        runCatching {
                            forecastParser.parse(row.location.id, it.fetchedAtEpochMillis, it.rawJson)
                        }.getOrNull()
                    }
                )
            }
        }

    suspend fun getCachedLocationForecasts(): List<LocationForecast> =
        locationDao.getLocationsWithCache().map { row ->
            LocationForecast(
                location = row.location,
                forecast = row.forecastCache?.let {
                    runCatching {
                        forecastParser.parse(row.location.id, it.fetchedAtEpochMillis, it.rawJson)
                    }.getOrNull()
                }
            )
        }

    suspend fun getCachedWidgetForecasts(): List<LocationForecast> =
        locationDao.getWidgetLocationsWithCache().map { row ->
            LocationForecast(
                location = row.location,
                forecast = row.forecastCache?.let {
                    runCatching {
                        forecastParser.parse(row.location.id, it.fetchedAtEpochMillis, it.rawJson)
                    }.getOrNull()
                }
            )
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
        val json = weatherApi.forecast(
            latitude = location.latitude,
            longitude = location.longitude,
            temperatureUnit = settings.temperatureUnit,
            windSpeedUnit = settings.windSpeedUnit,
            precipitationUnit = settings.precipitationUnit
        )
        forecastCacheDao.upsert(
            ForecastCacheEntity(
                locationId = location.id,
                fetchedAtEpochMillis = Instant.now().toEpochMilli(),
                rawJson = gson.toJson(json)
            )
        )
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
}
