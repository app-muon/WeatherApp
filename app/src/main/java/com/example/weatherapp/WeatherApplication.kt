package com.example.weatherapp

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.weatherapp.data.api.ApiModule
import com.example.weatherapp.data.db.WeatherDatabase
import com.example.weatherapp.data.repository.LocationRepository
import com.example.weatherapp.data.repository.WeatherRepository
import com.example.weatherapp.data.repository.RefreshCoordinator
import com.example.weatherapp.ui.widget.updateWeatherWidgets
import com.example.weatherapp.ui.widget.observeWidgetChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.retryWhen
import java.time.Instant
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
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<ForecastRefreshWorker>(3, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ForecastRefreshWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

class AppContainer(context: Context) {
    val widgetTime = MutableStateFlow(Instant.now())
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val widgetChanges = Channel<Unit>(Channel.CONFLATED)
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

    val weatherRepository = WeatherRepository(
        database = database,
        marineApi = ApiModule.marineApi,
        settingsRepository = settingsRepository,
        providers = providers,
        forecastParser = forecastParser,
        marineParser = marineParser,
        forecastJsonCodec = forecastJsonCodec
    )

    val refreshCoordinator = RefreshCoordinator(weatherRepository, applicationScope,
        onUnexpectedError = { android.util.Log.e("WeatherRefresh", "Refresh could not read or save weather", it) })

    val locationRepository = LocationRepository(
        database = database,
        geocodingApi = ApiModule.geocodingApi,
        defaultSource = { weatherRepository.defaultProvider(it) }
    )

    init {
        applicationScope.launch {
            observeWidgetChanges(weatherRepository.observeWidgetForecasts(), refreshCoordinator.activity)
                .retryWhen { error, attempt ->
                    if (error is CancellationException) throw error
                    android.util.Log.e("WeatherWidget", "Could not observe widget forecasts", error)
                    delay(((attempt + 1) * 1000).coerceAtMost(30_000))
                    true
                }.collect { widgetChanges.trySend(Unit) }
        }
        applicationScope.launch {
            for (change in widgetChanges) {
                try {
                    updateWeatherWidgets(context.applicationContext)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w("WeatherWidget", "Widget update could not be delivered", error)
                }
            }
        }
    }
}
