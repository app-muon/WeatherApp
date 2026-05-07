package com.example.weatherapp.worker

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.weatherapp.AppContainer
import com.example.weatherapp.ui.widget.WeatherWidget

class ForecastRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = AppContainer(applicationContext)
        val result = container.weatherRepository.refreshAll()
        WeatherWidget().updateAll(applicationContext)
        return if (result.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        const val PERIODIC_WORK_NAME = "forecast_periodic_refresh"
        const val STALE_REFRESH_WORK_NAME = "forecast_stale_refresh"
    }
}

