package com.example.weatherapp.worker

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.weatherapp.WeatherApplication
import com.example.weatherapp.ui.widget.WeatherWidget

class ForecastRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as WeatherApplication).container
        val result = container.weatherRepository.refreshWidgetLocations()
        WeatherWidget().updateAll(applicationContext)
        return if (result.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        const val PERIODIC_WORK_NAME = "forecast_periodic_refresh"
        const val STALE_REFRESH_WORK_NAME = "forecast_stale_refresh"
    }
}
