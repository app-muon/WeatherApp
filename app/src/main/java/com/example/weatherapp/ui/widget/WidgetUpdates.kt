package com.example.weatherapp.ui.widget

import com.example.weatherapp.data.repository.ContentScope
import com.example.weatherapp.data.repository.LocationForecast
import com.example.weatherapp.data.repository.RefreshActivity
import com.example.weatherapp.domain.model.ProviderForecast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Only values rendered by a widget participate in update notifications. */
internal data class WidgetDisplayState(
    val locationId: Long,
    val revision: Long,
    val name: String,
    val timezone: String?,
    val forecast: ProviderForecast?,
    val usingFallback: Boolean,
    val failed: Boolean,
    val updating: Boolean,
    val refreshProblem: String?
)

internal fun widgetIsUpdating(item: LocationForecast, activity: RefreshActivity): Boolean =
    activity.isActive(item.location, ContentScope.Forecast) || activity.sources.any {
        it.locationId == item.location.id && it.revision == item.location.revision &&
            (it.sourceId == item.preferredProviderId || it.sourceId == item.forecast?.providerId)
    }

internal fun observeWidgetChanges(
    forecasts: Flow<List<LocationForecast>>,
    activity: Flow<RefreshActivity>
): Flow<List<WidgetDisplayState>> = combine(forecasts, activity) { items, refresh ->
    items.map { item ->
        WidgetDisplayState(item.location.id, item.location.revision, item.location.name, item.location.timezone,
            item.forecast, item.usingFallback, item.lastRefreshFailed, widgetIsUpdating(item, refresh),
            refresh.failure(item.location, ContentScope.Forecast))
    }
}.distinctUntilChanged()
