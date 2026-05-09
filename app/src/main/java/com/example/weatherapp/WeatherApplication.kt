package com.example.weatherapp

import android.app.Application
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.weatherapp.data.api.ApiModule
import com.example.weatherapp.data.db.WeatherDatabase
import com.example.weatherapp.data.repository.LocationRepository
import com.example.weatherapp.data.repository.WeatherRepository
import com.example.weatherapp.data.provider.AemetProvider
import com.example.weatherapp.data.provider.MetNorwayProvider
import com.example.weatherapp.data.provider.MetOfficeProvider
import com.example.weatherapp.data.provider.OpenMeteoProvider
import com.example.weatherapp.domain.mapper.ForecastParser
import com.example.weatherapp.domain.mapper.MarineParser
import com.example.weatherapp.domain.mapper.ProviderForecastJsonCodec
import com.example.weatherapp.settings.WeatherSettingsRepository
import com.example.weatherapp.worker.ForecastRefreshWorker
import com.google.gson.Gson
import java.util.concurrent.TimeUnit

class WeatherApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
        schedulePeriodicRefresh(applicationContext)
    }

    private fun schedulePeriodicRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<ForecastRefreshWorker>(3, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ForecastRefreshWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

class AppContainer(context: Context) {
    private val database = WeatherDatabase.getInstance(context)
    private val gson = Gson()
    private val forecastParser = ForecastParser()
    private val marineParser = MarineParser()
    private val forecastJsonCodec = ProviderForecastJsonCodec()
    val settingsRepository = WeatherSettingsRepository(context)
    private val providers = listOf(
        OpenMeteoProvider(ApiModule.weatherApi, forecastParser, gson),
        MetNorwayProvider(ApiModule.metNorwayApi, gson),
        MetOfficeProvider(ApiModule.metOfficeApi, BuildConfig.MET_OFFICE_API_KEY, gson),
        AemetProvider(ApiModule.aemetApi, BuildConfig.AEMET_API_KEY, gson)
    )

    val locationRepository = LocationRepository(
        locationDao = database.locationDao(),
        forecastCacheDao = database.forecastCacheDao(),
        marineCacheDao = database.marineCacheDao(),
        geocodingApi = ApiModule.geocodingApi
    )

    val weatherRepository = WeatherRepository(
        locationDao = database.locationDao(),
        forecastCacheDao = database.forecastCacheDao(),
        providerStatusDao = database.providerStatusDao(),
        forecastSourcePreferenceDao = database.forecastSourcePreferenceDao(),
        marineCacheDao = database.marineCacheDao(),
        marineApi = ApiModule.marineApi,
        settingsRepository = settingsRepository,
        providers = providers,
        forecastParser = forecastParser,
        marineParser = marineParser,
        forecastJsonCodec = forecastJsonCodec
    )
}
