package com.example.weatherapp.data.repository

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.weatherapp.data.api.MarineApiClient
import com.example.weatherapp.data.db.MarineCacheDao
import com.example.weatherapp.data.db.MarineCacheEntity
import com.example.weatherapp.data.db.ForecastCacheDao
import com.example.weatherapp.data.db.ForecastCacheEntity
import com.example.weatherapp.data.db.LocationDao
import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.data.db.ProviderStatusDao
import com.example.weatherapp.data.db.ProviderStatusEntity
import com.example.weatherapp.data.provider.WeatherProvider
import com.example.weatherapp.data.provider.WeatherProviderIds
import com.example.weatherapp.data.provider.countryCodeOrName
import com.example.weatherapp.domain.mapper.ForecastParser
import com.example.weatherapp.domain.mapper.MarineParser
import com.example.weatherapp.domain.mapper.ProviderForecastJsonCodec
import com.example.weatherapp.domain.model.MarineConditions
import com.example.weatherapp.domain.model.ForecastSourcePreference
import com.example.weatherapp.domain.model.ProviderForecast
import com.example.weatherapp.domain.model.ProviderStatus
import com.example.weatherapp.domain.model.WeatherUnits
import com.example.weatherapp.settings.WeatherSettingsRepository
import com.example.weatherapp.worker.ForecastRefreshWorker
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

data class LocationForecast(
    val location: LocationEntity,
    val forecast: ProviderForecast?,
    val providerForecasts: List<ProviderForecast>,
    val providerStatuses: List<ProviderStatus>,
    val marineConditions: MarineConditions?,
    val forecastSourcePreference: ForecastSourcePreference?,
    val lastRefreshFailed: Boolean = false
)

data class ProviderOption(
    val id: String,
    val displayName: String,
    val shortName: String
)

class WeatherRepository(
    private val locationDao: LocationDao,
    private val forecastCacheDao: ForecastCacheDao,
    private val providerStatusDao: ProviderStatusDao,
    private val forecastSourcePreferenceDao: com.example.weatherapp.data.db.ForecastSourcePreferenceDao,
    private val marineCacheDao: MarineCacheDao,
    private val marineApi: MarineApiClient,
    private val settingsRepository: WeatherSettingsRepository,
    private val providers: List<WeatherProvider>,
    private val forecastParser: ForecastParser,
    private val marineParser: MarineParser,
    private val forecastJsonCodec: ProviderForecastJsonCodec
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

    suspend fun refreshAllDisplayProviders(): Result<Unit> {
        val rows = locationDao.getLocationsWithCache()
        var failure: Throwable? = null
        val settings = settingsRepository.settings.first()
        val units = settings.toWeatherUnits()
        rows.forEach { row ->
            val result = refreshProviderFallback(
                location = row.location,
                providerIds = displayProviderFallbackOrder(row.location, row.forecastSourcePreference?.selectedProviderId),
                units = units
            )
            if (result.isFailure) failure = result.exceptionOrNull()
        }
        return failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    suspend fun refreshLocation(location: LocationEntity): Result<Unit> = runCatching {
        refreshAvailableProviders(location)
    }

    suspend fun refreshAvailableProviders(location: LocationEntity) {
        val settings = settingsRepository.settings.first()
        val units = settings.toWeatherUnits()
        val preferredId = resolveDefaultProvider(location, forecastSourcePreferenceDao.get(location.id)?.selectedProviderId)
        var firstFailure: Throwable? = null
        availableProviders(location).forEach { provider ->
            val result = fetchAndCacheProvider(location, provider, units)
            // Surface failures from the provider the user actually sees (their configured/default
            // source) as well as Open-Meteo, instead of silently swallowing them.
            if (
                result.isFailure &&
                firstFailure == null &&
                (provider.id == preferredId || provider.id == WeatherProviderIds.OPEN_METEO)
            ) {
                firstFailure = result.exceptionOrNull()
            }
        }
        if (firstFailure != null) throw firstFailure as Throwable
    }

    suspend fun refreshLocation(locationId: Long): Result<Unit> {
        val location = locationDao.getById(locationId)
            ?: return Result.failure(IllegalArgumentException("Location not found"))
        return refreshLocation(location)
    }

    suspend fun refreshWidgetLocations(): Result<Unit> {
        val settings = settingsRepository.settings.first()
        val units = settings.toWeatherUnits()
        var failure: Throwable? = null
        locationDao.getWidgetLocationsWithCache().forEach { row ->
            val result = refreshProviderFallback(
                location = row.location,
                providerIds = displayProviderFallbackOrder(row.location, row.forecastSourcePreference?.selectedProviderId),
                units = units
            )
            if (result.isFailure && failure == null) failure = result.exceptionOrNull()
        }
        return failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    companion object {
        const val STALE_AFTER_MILLIS = 6L * 60L * 60L * 1000L

        fun enqueueRefreshIfStale(workManager: WorkManager, forecasts: List<LocationForecast>) {
            val now = Instant.now().toEpochMilli()
            val stale = forecasts.any { it.forecast == null || now - it.forecast.fetchedAt.toEpochMilli() > STALE_AFTER_MILLIS }
            if (stale) {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                workManager.enqueueUniqueWork(
                    ForecastRefreshWorker.STALE_REFRESH_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<ForecastRefreshWorker>()
                        .setConstraints(constraints)
                        .build()
                )
            }
        }
    }

    suspend fun providerOptions(location: LocationEntity): List<ProviderOption> =
        listOf(ProviderOption(WeatherProviderIds.AUTO, "Default source", "Default")) +
            availableProviders(location).map { ProviderOption(it.id, it.displayName, it.shortName) }

    suspend fun setForecastSource(locationId: Long, providerId: String) {
        forecastSourcePreferenceDao.upsert(com.example.weatherapp.data.db.ForecastSourcePreferenceEntity(locationId, providerId))
    }

    suspend fun initDefaultForecastSource(locationId: Long) {
        val location = locationDao.getById(locationId) ?: return
        val existing = forecastSourcePreferenceDao.get(locationId)
        if (existing != null) return
        val providerId = resolveDefaultProvider(location, null)
        forecastSourcePreferenceDao.upsert(com.example.weatherapp.data.db.ForecastSourcePreferenceEntity(locationId, providerId))
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
            marineParser.parse(marineJson, weatherForecast.current?.time ?: java.time.ZonedDateTime.now())
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
        val forecasts = row.forecastCaches.mapNotNull { cache -> decodeCachedForecast(cache) }
            .filter { it.hasForecastData() }
            .sortedWith(compareBy<ProviderForecast> { providerSortOrder(it.providerId) }.thenBy { it.providerName })
        val forecast = selectDisplayForecast(
            location = row.location,
            forecastSourceProviderId = row.forecastSourcePreference?.selectedProviderId,
            forecasts = forecasts
        )
        val marineConditions = row.marineCache?.let {
            runCatching {
                marineParser.parse(it.rawJson, forecast?.current?.time ?: java.time.Instant.ofEpochMilli(it.fetchedAtEpochMillis).atZone(java.time.ZoneId.systemDefault()))
            }.getOrNull()
        }
        return LocationForecast(
            location = row.location,
            forecast = forecast,
            providerForecasts = forecasts,
            providerStatuses = row.providerStatuses.map { it.toProviderStatus() },
            marineConditions = marineConditions,
            forecastSourcePreference = row.forecastSourcePreference?.let {
                ForecastSourcePreference(it.locationId, it.selectedProviderId)
            }
        )
    }

    private suspend fun fetchAndCacheProvider(
        location: LocationEntity,
        provider: WeatherProvider,
        units: WeatherUnits
    ): Result<Unit> {
        val fetchedAt = Instant.now()
        val previousStatus = providerStatusDao.get(location.id, provider.id)
        providerStatusDao.upsert(
            ProviderStatusEntity(
                location.id,
                provider.id,
                fetchedAt.toEpochMilli(),
                previousStatus?.lastSuccessAtEpochMillis,
                null
            )
        )
        return runCatching {
            val result = provider.fetchForecast(location, units)
            val forecast = result.forecast.copy(fetchedAt = fetchedAt)
            forecastCacheDao.upsert(
                ForecastCacheEntity(
                    locationId = location.id,
                    providerId = provider.id,
                    fetchedAtEpochMillis = fetchedAt.toEpochMilli(),
                    rawJson = result.rawJson,
                    normalisedJson = forecastJsonCodec.encode(forecast)
                )
            )
            providerStatusDao.upsert(
                ProviderStatusEntity(location.id, provider.id, fetchedAt.toEpochMilli(), fetchedAt.toEpochMilli(), null)
            )
            if (provider.id == WeatherProviderIds.OPEN_METEO) {
                refreshMarine(location, result.rawJson)
            }
        }.onFailure { error ->
            providerStatusDao.upsert(
                ProviderStatusEntity(
                    location.id,
                    provider.id,
                    fetchedAt.toEpochMilli(),
                    previousStatus?.lastSuccessAtEpochMillis,
                    error.message ?: error::class.java.simpleName
                )
            )
        }
    }

    private suspend fun refreshProviderFallback(
        location: LocationEntity,
        providerIds: List<String>,
        units: WeatherUnits
    ): Result<Unit> {
        var firstFailure: Throwable? = null
        for (providerId in providerIds) {
            val provider = providers.firstOrNull { it.id == providerId } ?: continue
            if (!runCatching { provider.isAvailableFor(location) }.getOrDefault(false)) continue
            val result = fetchAndCacheProvider(location, provider, units)
            if (result.isSuccess) return Result.success(Unit)
            if (firstFailure == null) firstFailure = result.exceptionOrNull()
        }
        return firstFailure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    private suspend fun availableProviders(location: LocationEntity): List<WeatherProvider> =
        providers.filter { provider -> runCatching { provider.isAvailableFor(location) }.getOrDefault(false) }

    private fun resolveDefaultProvider(location: LocationEntity, forecastSourceProviderId: String?): String {
        if (forecastSourceProviderId != null && forecastSourceProviderId != WeatherProviderIds.AUTO) return forecastSourceProviderId
        val countryCode = location.countryCodeOrName()
        return when {
            countryCode == "GB" && providers.any { it.id == WeatherProviderIds.MET_OFFICE && it.isConfigured } -> WeatherProviderIds.MET_OFFICE
            countryCode == "ES" && providers.any { it.id == WeatherProviderIds.AEMET && it.isConfigured } -> WeatherProviderIds.AEMET
            else -> WeatherProviderIds.OPEN_METEO
        }
    }

    private fun displayProviderFallbackOrder(location: LocationEntity, forecastSourceProviderId: String?): List<String> {
        val requested = resolveDefaultProvider(location, forecastSourceProviderId)
        return listOf(requested, WeatherProviderIds.OPEN_METEO, WeatherProviderIds.MET_NORWAY).distinct()
    }

    private fun selectDisplayForecast(
        location: LocationEntity,
        forecastSourceProviderId: String?,
        forecasts: List<ProviderForecast>
    ): ProviderForecast? {
        val candidates = displayProviderFallbackOrder(location, forecastSourceProviderId)
            .mapNotNull { id -> forecasts.firstOrNull { it.providerId == id } }
        return candidates.firstOrNull { !it.isStale() }
            ?: candidates.firstOrNull()
            ?: forecasts.firstOrNull { !it.isStale() }
            ?: forecasts.firstOrNull()
    }

    private fun ProviderForecast.isStale(now: Long = Instant.now().toEpochMilli()): Boolean =
        now - fetchedAt.toEpochMilli() > STALE_AFTER_MILLIS

    private fun decodeCachedForecast(cache: ForecastCacheEntity): ProviderForecast? =
        runCatching { forecastJsonCodec.decode(cache.normalisedJson) }
            .getOrElse {
                if (cache.providerId == WeatherProviderIds.OPEN_METEO) {
                    runCatching {
                        forecastParser.parse(cache.locationId, cache.fetchedAtEpochMillis, cache.rawJson)
                            .copy(providerId = WeatherProviderIds.OPEN_METEO, providerName = "Open-Meteo")
                    }.getOrNull()
                } else {
                    null
                }
            }

    private fun providerSortOrder(providerId: String): Int = when (providerId) {
        WeatherProviderIds.OPEN_METEO -> 0
        WeatherProviderIds.MET_NORWAY -> 1
        WeatherProviderIds.MET_OFFICE -> 2
        WeatherProviderIds.AEMET -> 3
        else -> 99
    }

    private fun ProviderForecast.hasForecastData(): Boolean =
        current != null || hourly.isNotEmpty() || daily.isNotEmpty()
}

private fun com.example.weatherapp.settings.WeatherSettings.toWeatherUnits() = WeatherUnits(
    temperatureUnit = temperatureUnit,
    windSpeedUnit = windSpeedUnit,
    precipitationUnit = precipitationUnit
)

private fun ProviderStatusEntity.toProviderStatus() = ProviderStatus(
    providerId = providerId,
    locationId = locationId,
    lastFetchedAt = lastFetchedAtEpochMillis?.let { Instant.ofEpochMilli(it) },
    lastSuccessAt = lastSuccessAtEpochMillis?.let { Instant.ofEpochMilli(it) },
    lastError = lastError
)
