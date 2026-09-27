package com.example.weatherapp.data.repository

import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.weatherapp.data.api.MarineApiClient
import com.example.weatherapp.data.db.*
import com.example.weatherapp.data.provider.WeatherProvider
import com.example.weatherapp.data.provider.WeatherProviderIds
import com.example.weatherapp.data.provider.countryCodeOrName
import com.example.weatherapp.domain.mapper.ForecastParser
import com.example.weatherapp.domain.mapper.MarineParser
import com.example.weatherapp.domain.mapper.ProviderForecastJsonCodec
import com.example.weatherapp.domain.model.*
import com.example.weatherapp.settings.WeatherSettingsRepository
import com.example.weatherapp.worker.ForecastRefreshWorker
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import retrofit2.HttpException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

data class LocationForecast(
    val location: LocationEntity,
    val forecast: ProviderForecast?,
    val providerForecasts: List<ProviderForecast>,
    val providerStatuses: List<ProviderStatus>,
    val marineConditions: MarineConditions?,
    val forecastSourcePreference: ForecastSourcePreference?,
    val preferredProviderId: String,
    val marineFetchedAt: Instant? = null,
    val marineStatus: MarineStatusEntity? = null,
    val lastRefreshFailed: Boolean = false
) {
    val usingFallback: Boolean get() = forecast != null && forecast.providerId != preferredProviderId
    val preferredSourceError: String? get() = providerStatuses.find { it.providerId == preferredProviderId }?.lastError
    val marineUnavailable: Boolean get() = marineStatus?.unavailableAtEpochMillis != null && marineStatus.lastError == null
}

data class ProviderOption(val id: String, val displayName: String, val shortName: String)

class WeatherRepository(
    private val database: WeatherDatabase,
    private val marineApi: MarineApiClient,
    private val settingsRepository: WeatherSettingsRepository,
    private val providers: List<WeatherProvider>,
    private val forecastParser: ForecastParser,
    private val marineParser: MarineParser,
    private val forecastJsonCodec: ProviderForecastJsonCodec,
    private val clock: Clock = Clock.systemUTC()
) : RefreshStore {
    private val gson = Gson()
    private val locationDao = database.locationDao()
    private val forecastCacheDao = database.forecastCacheDao()
    private val providerStatusDao = database.providerStatusDao()
    private val preferenceDao = database.forecastSourcePreferenceDao()
    private val marineCacheDao = database.marineCacheDao()
    private val marineStatusDao = database.marineStatusDao()

    fun observeLocationForecasts(): Flow<List<LocationForecast>> =
        locationDao.observeLocationsWithCache().map { rows -> rows.map(::toLocationForecast) }

    fun observeWidgetForecasts(redraws: Flow<Instant> = kotlinx.coroutines.flow.flowOf(clock.instant())): Flow<List<LocationForecast>> =
        combine(locationDao.observeWidgetLocationsWithCache(), redraws) { rows, _ -> rows.map(::toLocationForecast) }

    suspend fun locationIds(): List<Long> = locationDao.getLocations().map { it.id }
    override suspend fun location(id: Long): LocationEntity? = locationDao.getById(id)

    override suspend fun unavailableAt(location: LocationEntity, source: String): Instant? = database.withTransaction {
        if (source != MARINE_SOURCE || locationDao.getById(location.id)?.revision != location.revision) return@withTransaction null
        marineStatusDao.get(location.id)?.takeIf { it.lastError == null }?.unavailableAtEpochMillis?.let(Instant::ofEpochMilli)
    }

    override suspend fun isPermanentFailure(location: LocationEntity, source: String): Boolean =
        (if (source == MARINE_SOURCE) marineStatusDao.get(location.id)?.lastError
            else providerStatusDao.get(location.id, source)?.lastError) == CONFIGURATION_ERROR

    override suspend fun sources(location: LocationEntity, scope: ContentScope): List<String> = when (scope) {
        ContentScope.Marine -> listOf(MARINE_SOURCE)
        ContentScope.Compare -> availableProviders(location).map { it.id }
        ContentScope.Forecast -> fallbackOrder(location, preferenceDao.get(location.id)?.selectedProviderId)
    }

    override suspend fun cachedAt(location: LocationEntity, source: String): Instant? = database.withTransaction {
        if (locationDao.getById(location.id)?.revision != location.revision) return@withTransaction null
        if (source == MARINE_SOURCE) {
            marineCacheDao.get(location.id)?.takeIf { cache ->
                runCatching { marineParser.parse(cache.rawJson, clock.instant().atZone(location.zone()))?.hasData() == true }.getOrDefault(false)
            }?.fetchedAtEpochMillis?.let(Instant::ofEpochMilli)
        } else {
            forecastCacheDao.get(location.id, source)?.let(::decodeCachedForecast)
                ?.takeIf { it.hasData() }?.fetchedAt
        }
    }

    override suspend fun fetch(location: LocationEntity, source: String): SourceOutcome {
        val attempt = clock.millis()
        if (!commit(location) {
            if (source == MARINE_SOURCE) {
                val old = marineStatusDao.get(location.id)
                marineStatusDao.upsert(MarineStatusEntity(location.id, attempt, old?.lastSuccessAtEpochMillis, old?.lastError, old?.unavailableAtEpochMillis))
            } else {
                val old = providerStatusDao.get(location.id, source)
                providerStatusDao.upsert(ProviderStatusEntity(location.id, source, attempt, old?.lastSuccessAtEpochMillis, old?.lastError))
            }
        }) return SourceOutcome(location.id, source, RefreshResult.Discarded)
        try {
            if (source == MARINE_SOURCE) {
                val raw = marineApi.marine(latitude = location.latitude, longitude = location.longitude)
                val conditions = marineParser.parse(raw, clock.instant().atZone(location.zone()))
                val completed = clock.millis()
                if (conditions?.hasData() != true) {
                    val committed = commit(location) {
                        val old = marineStatusDao.get(location.id)
                        marineStatusDao.upsert(MarineStatusEntity(location.id, attempt, old?.lastSuccessAtEpochMillis, null, completed))
                    }
                    return SourceOutcome(location.id, source, if (committed) RefreshResult.Unavailable else RefreshResult.Discarded)
                }
                val committed = commit(location) {
                    marineCacheDao.upsert(MarineCacheEntity(location.id, completed, gson.toJson(raw)))
                    marineStatusDao.upsert(MarineStatusEntity(location.id, attempt, completed, null))
                }
                return SourceOutcome(location.id, source, if (committed) RefreshResult.Updated else RefreshResult.Discarded)
            }
            val provider = providers.firstOrNull { it.id == source }
                ?: throw ProviderConfigurationException()
            if (!provider.isAvailableFor(location)) throw ProviderConfigurationException()
            val settings = settingsRepository.settings.first()
            val result = provider.fetchForecast(location, WeatherUnits(settings.temperatureUnit, settings.windSpeedUnit, settings.precipitationUnit))
            require(result.forecast.locationId == location.id && result.forecast.providerId == source && result.forecast.hasData()) { "Empty or invalid forecast" }
            val completed = clock.instant()
            val forecast = result.forecast.copy(fetchedAt = completed)
            val normalised = forecastJsonCodec.encode(forecast)
            val committed = commit(location) {
                forecastCacheDao.upsert(ForecastCacheEntity(location.id, source, completed.toEpochMilli(), result.rawJson, normalised))
                providerStatusDao.upsert(ProviderStatusEntity(location.id, source, attempt, completed.toEpochMilli(), null))
            }
            return SourceOutcome(location.id, source, if (committed) RefreshResult.Updated else RefreshResult.Discarded)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val permanent = error is ProviderConfigurationException ||
                (error is HttpException && error.code() in 400..499 && error.code() !in listOf(408, 429))
            val message = if (permanent) CONFIGURATION_ERROR else "Update failed. Check your connection and retry."
            val committed = commit(location) {
                if (source == MARINE_SOURCE) {
                    val old = marineStatusDao.get(location.id)
                    marineStatusDao.upsert(MarineStatusEntity(location.id, attempt, old?.lastSuccessAtEpochMillis, message))
                } else {
                    val old = providerStatusDao.get(location.id, source)
                    providerStatusDao.upsert(ProviderStatusEntity(location.id, source, attempt, old?.lastSuccessAtEpochMillis, message))
                }
            }
            return SourceOutcome(location.id, source, if (committed) RefreshResult.Failed else RefreshResult.Discarded, message, committed && !permanent)
        }
    }

    /** A replacement/delete cannot interleave between the revision check and either write. */
    private suspend fun commit(location: LocationEntity, write: suspend () -> Unit): Boolean {
        val committed = database.withTransaction {
            currentCoroutineContext().ensureActive()
            if (locationDao.getById(location.id)?.revision != location.revision) false
            else { write(); true }
        }
        return committed
    }

    suspend fun providerOptions(location: LocationEntity): List<ProviderOption> =
        availableProviders(location).map { ProviderOption(it.id, it.displayName, it.shortName) }

    suspend fun setForecastSource(locationId: Long, providerId: String) {
        database.withTransaction {
            if (locationDao.getById(locationId) != null) preferenceDao.upsert(ForecastSourcePreferenceEntity(locationId, providerId))
        }
    }

    private suspend fun availableProviders(location: LocationEntity): List<WeatherProvider> =
        providers.filter { it.isAvailableFor(location) }

    fun defaultProvider(location: LocationEntity, preference: String? = null): String {
        if (preference != null && preference != WeatherProviderIds.AUTO) return preference
        return when {
            location.countryCodeOrName() == "GB" && providers.any { it.id == WeatherProviderIds.MET_OFFICE && it.isConfigured } -> WeatherProviderIds.MET_OFFICE
            location.countryCodeOrName() == "ES" && providers.any { it.id == WeatherProviderIds.AEMET && it.isConfigured } -> WeatherProviderIds.AEMET
            else -> WeatherProviderIds.OPEN_METEO
        }
    }

    private fun fallbackOrder(location: LocationEntity, preference: String?): List<String> =
        listOf(defaultProvider(location, preference), WeatherProviderIds.OPEN_METEO, WeatherProviderIds.MET_NORWAY).distinct()

    private fun toLocationForecast(row: LocationWithCaches): LocationForecast {
        val forecasts = row.forecastCaches.mapNotNull(::decodeCachedForecast).filter { it.hasData() }
        val preferred = defaultProvider(row.location, row.forecastSourcePreference?.selectedProviderId)
        val candidates = fallbackOrder(row.location, preferred).mapNotNull { id -> forecasts.find { it.providerId == id } }
        val failedIds = row.providerStatuses.filter { it.lastError != null }.map { it.providerId }.toSet()
        val forecast = selectDisplayForecast(candidates, forecasts, failedIds, clock.instant())
        val marine = row.marineCache?.let { cache ->
            runCatching { marineParser.parse(cache.rawJson, clock.instant().atZone(row.location.zone())) }.getOrNull()
        }
        return LocationForecast(
            location = row.location,
            forecast = forecast,
            providerForecasts = forecasts,
            providerStatuses = row.providerStatuses.map { ProviderStatus(it.providerId, it.locationId,
                it.lastFetchedAtEpochMillis?.let(Instant::ofEpochMilli), it.lastSuccessAtEpochMillis?.let(Instant::ofEpochMilli), it.lastError) },
            marineConditions = marine,
            forecastSourcePreference = row.forecastSourcePreference?.let { ForecastSourcePreference(it.locationId, it.selectedProviderId) },
            preferredProviderId = preferred,
            marineFetchedAt = row.marineCache?.fetchedAtEpochMillis?.let(Instant::ofEpochMilli),
            marineStatus = row.marineStatus,
            lastRefreshFailed = if (forecast == null) failedIds.isNotEmpty() else forecast.providerId in failedIds
        )
    }

    private fun decodeCachedForecast(cache: ForecastCacheEntity): ProviderForecast? =
        runCatching { forecastJsonCodec.decode(cache.normalisedJson) }.getOrNull()
            ?: if (cache.providerId == WeatherProviderIds.OPEN_METEO) runCatching {
                forecastParser.parse(cache.locationId, cache.fetchedAtEpochMillis, cache.rawJson)
                    .copy(providerId = WeatherProviderIds.OPEN_METEO, providerName = "Open-Meteo")
            }.getOrNull() else null

    companion object {
        const val STALE_AFTER_MILLIS = 6L * 60 * 60 * 1000
        private const val CONFIGURATION_ERROR = "Source unavailable. Check its configuration."

        fun enqueueRefreshIfStale(workManager: WorkManager, forecasts: List<LocationForecast>, now: Instant = Instant.now()) {
            if (forecasts.any { !isFresh(it.forecast?.fetchedAt, now, Duration.ofMillis(STALE_AFTER_MILLIS)) }) {
                workManager.enqueueUniqueWork(ForecastRefreshWorker.STALE_REFRESH_WORK_NAME, ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<ForecastRefreshWorker>()
                        .setInputData(workDataOf(ForecastRefreshWorker.SKIP_PERMANENT_FAILURES to true))
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
            }
        }
    }
}

class ProviderConfigurationException : IllegalStateException("Provider is not configured")

fun selectDisplayForecast(candidates: List<ProviderForecast>, all: List<ProviderForecast>, failedIds: Set<String>, now: Instant): ProviderForecast? =
    candidates.firstOrNull { it.providerId !in failedIds && isFresh(it.fetchedAt, now, FOREGROUND_MAX_AGE) }
        ?: candidates.firstOrNull { it.providerId !in failedIds && isFresh(it.fetchedAt, now, Duration.ofHours(6)) }
        ?: all.maxByOrNull { it.fetchedAt }

fun MarineConditions.hasData(): Boolean = listOf(seaSurfaceTemperature, waveHeight, wavePeriod, waveDirection?.toDouble()).any { it?.isFinite() == true }

fun ProviderForecast.hasData(): Boolean {
    fun valid(values: List<Double?>) = values.all { it == null || it.isFinite() }
    fun known(code: Int?) = code != null && com.example.weatherapp.domain.mapper.WeatherCodeMapper.condition(code).icon != WeatherIcon.Unknown
    if (current?.let { !valid(listOf(it.temperature, it.feelsLike, it.precipitation, it.rain, it.pressure, it.windSpeed)) } == true ||
        hourly.any { !valid(listOf(it.temperature, it.feelsLike, it.precipitation, it.rain, it.pressure, it.windSpeed, it.visibility, it.uvIndex)) } ||
        daily.any { !valid(listOf(it.tempMin, it.tempMax, it.precipitationSum, it.rainSum, it.windSpeedMax, it.uvIndexMax)) }) return false
    return current?.let { it.temperature != null || it.windSpeed != null || known(it.weatherCode) } == true ||
        hourly.any { it.temperature != null || it.windSpeed != null || known(it.weatherCode) } ||
        daily.any { it.tempMin != null || it.tempMax != null || known(it.weatherCode) }
}
