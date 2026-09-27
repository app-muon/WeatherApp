package com.example.weatherapp.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.weatherapp.WeatherApplication
import com.example.weatherapp.data.repository.RefreshRequest
import com.example.weatherapp.data.repository.ContentScope
import com.example.weatherapp.data.repository.BACKGROUND_MAX_AGE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ForecastRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val container = (applicationContext as WeatherApplication).container
            val outcomes = container.refreshCoordinator.refresh(RefreshRequest(
                container.weatherRepository.locationIds(), ContentScope.Forecast, maxAge = BACKGROUND_MAX_AGE,
                skipPermanentFailures = runAttemptCount > 0 || inputData.getBoolean(SKIP_PERMANENT_FAILURES, false)
            ))
            if (shouldRetryRefresh(outcomes.any { it.retryable }, runAttemptCount)) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            Log.e("WeatherRefresh", "Background refresh could not read saved locations", error)
            if (shouldRetryRefresh(true, runAttemptCount)) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val PERIODIC_WORK_NAME = "forecast_periodic_refresh"
        const val STALE_REFRESH_WORK_NAME = "forecast_stale_refresh"
        const val SKIP_PERMANENT_FAILURES = "skip_permanent_failures"
    }
}

fun shouldRetryRefresh(hasTransientFailure: Boolean, runAttemptCount: Int): Boolean =
    hasTransientFailure && runAttemptCount < 2
