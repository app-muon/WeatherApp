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
import com.example.weatherapp.domain.mapper.ForecastParser
import com.example.weatherapp.settings.WeatherSettingsRepository
import com.example.weatherapp.worker.ForecastRefreshWorker
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
    private val settingsRepository = WeatherSettingsRepository(context)
    private val forecastParser = ForecastParser()

    val locationRepository = LocationRepository(
        locationDao = database.locationDao(),
        forecastCacheDao = database.forecastCacheDao(),
        geocodingApi = ApiModule.geocodingApi
    )

    val weatherRepository = WeatherRepository(
        locationDao = database.locationDao(),
        forecastCacheDao = database.forecastCacheDao(),
        weatherApi = ApiModule.weatherApi,
        settingsRepository = settingsRepository,
        forecastParser = forecastParser
    )
}

