package com.example.weatherapp.ui.widget

import android.content.Context
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.semantics.semantics
import androidx.glance.semantics.contentDescription
import androidx.work.WorkManager
import com.example.weatherapp.MainActivity
import com.example.weatherapp.WeatherApplication
import com.example.weatherapp.data.repository.*
import com.example.weatherapp.domain.mapper.WeatherCodeMapper
import com.example.weatherapp.domain.model.zone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val cyan = ColorProvider(Color(0xFF00E5FF))
private val primary = ColorProvider(Color(0xFFE8F7FF))
private val secondary = ColorProvider(Color(0xFFC5D8DE))

class WeatherWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as WeatherApplication).container
        val forecasts = container.weatherRepository.observeWidgetForecasts(container.widgetTime)
        val initial = forecasts.first()
        val initialMetrics = measureWidgetLayout(context)
        WeatherRepository.enqueueRefreshIfStale(WorkManager.getInstance(context), initial)
        provideContent {
            var layoutMetrics by remember { mutableStateOf(initialMetrics) }
            DisposableEffect(context) {
                val listener = object : ComponentCallbacks {
                    override fun onConfigurationChanged(config: Configuration) {
                        layoutMetrics = measureWidgetLayout(context.createConfigurationContext(config))
                    }
                    override fun onLowMemory() = Unit
                }
                context.registerComponentCallbacks(listener)
                onDispose { context.unregisterComponentCallbacks(listener) }
            }
            ObserveWeatherWidget(forecasts, initial, container.refreshCoordinator.activity, container.widgetTime, layoutMetrics)
        }
    }
}

@Composable
internal fun ObserveWeatherWidget(
    forecasts: Flow<List<LocationForecast>>,
    initial: List<LocationForecast>,
    refreshActivity: StateFlow<RefreshActivity>,
    time: StateFlow<Instant>,
    layoutMetrics: WidgetLayoutMetrics
) {
    val items by forecasts.collectAsState(initial)
    val activity by refreshActivity.collectAsState()
    val now by time.collectAsState()
    WeatherWidgetContent(items, activity, now, layoutMetrics)
}

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        (context.applicationContext as WeatherApplication).container.widgetTime.value = Instant.now()
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}

@Composable
internal fun WeatherWidgetContent(items: List<LocationForecast>, activity: RefreshActivity, now: Instant, layoutMetrics: WidgetLayoutMetrics) {
    val context = LocalContext.current
    val size = LocalSize.current
    val layout = chooseWidgetLayout(size.width.value, size.height.value, layoutMetrics, items.size.coerceIn(1, 2))
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(Color(0xFF00E5FF)).padding(2.dp)
            .background(Color(0xFFFF3DF2)).padding(1.dp)
            .background(Color(0xFF050711)).padding(5.dp),
        verticalAlignment = Alignment.Vertical.Top
    ) {
        when {
            items.isEmpty() -> OpenLocationsText("Choose locations", context)
            layout == WidgetLayout.Enlarge -> {
                Text("Enlarge widget to see both forecasts",
                    modifier = GlanceModifier.fillMaxSize().clickable(openAppAction(context, items.first().location.id)).padding(4.dp),
                    style = TextStyle(fontSize = 14.sp, color = cyan))
            }
            else -> {
                items.take(2).forEachIndexed { index, item ->
                    if (index > 0) Spacer(GlanceModifier.height(6.dp))
                    WeatherWidgetRow(item, widgetIsUpdating(item, activity), context, now,
                        compact = layout == WidgetLayout.Compact,
                        refreshProblem = activity.failure(item.location, ContentScope.Forecast))
                }
                if (items.size == 1) {
                    Spacer(GlanceModifier.height(6.dp))
                    OpenLocationsText("Add second location", context)
                }
            }
        }
    }
}

@Composable
private fun WeatherWidgetRow(item: LocationForecast, active: Boolean, context: Context, now: Instant, compact: Boolean, refreshProblem: String?) {
    val forecast = item.forecast
    val zone = item.location.zone()
    val today = now.atZone(zone).toLocalDate()
    Column(GlanceModifier.fillMaxWidth().clickable(openAppAction(context, item.location.id))) {
        val status = refreshProblem ?: widgetStatus(item, active, now)
        if (compact) {
            Row(GlanceModifier.fillMaxWidth().semantics { contentDescription = "${item.location.name}. $status" }) {
                Text(item.location.name, modifier = GlanceModifier.defaultWeight(), maxLines = 1,
                    style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, color = cyan))
                Text(if (refreshProblem != null) "Update failed" else compactWidgetStatus(item, active),
                    modifier = GlanceModifier.defaultWeight(), maxLines = 1,
                    style = TextStyle(fontSize = 10.sp, color = secondary))
            }
        } else {
            Text(item.location.name, maxLines = 1,
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, color = cyan))
            Text(status, maxLines = 1, style = TextStyle(fontSize = 10.sp, color = secondary))
        }
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            val current = forecast?.current
            val conditionsTime = current?.time?.withZoneSameInstant(zone)
            val currentLabel = if (conditionsTime == null) "Now"
                else conditionsTime.format(DateTimeFormatter.ofPattern(if (conditionsTime.toLocalDate() == today) "HH:mm" else "d/M HH:mm"))
            WeatherCell(currentLabel, current?.weatherCode, current?.temperature.temp(), GlanceModifier.defaultWeight())
            repeat(3) { offset ->
                val date = today.plusDays(offset.toLong())
                val day = forecast?.daily?.find { it.date == date }
                WeatherCell(date.format(DateTimeFormatter.ofPattern("EEE d")), day?.weatherCode,
                    day?.let { it.tempMax.temp() + "/" + it.tempMin.temp() } ?: "—", GlanceModifier.defaultWeight())
            }
        }
    }
}

private fun compactWidgetStatus(item: LocationForecast, active: Boolean): String {
    if (active) return "Updating…"
    val forecast = item.forecast ?: return if (item.lastRefreshFailed) "Update failed" else "Unavailable"
    val time = forecast.fetchedAt.atZone(item.location.zone()).format(DateTimeFormatter.ofPattern("d/M HH:mm"))
    return (if (item.lastRefreshFailed) "Failed · " else if (item.usingFallback) "Fallback · " else "") + time
}

@Composable
private fun WeatherCell(label: String, code: Int?, temperature: String, modifier: GlanceModifier) {
    Column(modifier.padding(end = 2.dp)) {
        Text(label, maxLines = 1, style = TextStyle(fontSize = 10.sp, color = secondary))
        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
            Image(ImageProvider(WeatherCodeMapper.drawableRes(code ?: -1)),
                contentDescription = WeatherCodeMapper.condition(code ?: -1).label,
                modifier = GlanceModifier.size(18.dp))
            Text(temperature, maxLines = 1,
                style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, color = primary))
        }
    }
}

internal fun widgetStatus(item: LocationForecast, active: Boolean, now: Instant): String {
    val forecast = item.forecast
    if (active) return if (forecast == null) "Loading…" else "Updating…"
    if (forecast == null) return if (item.lastRefreshFailed) "Update failed · tap to retry" else "Forecast unavailable · tap to open"
    val updated = forecast.fetchedAt.atZone(item.location.zone()).format(DateTimeFormatter.ofPattern("d/M HH:mm"))
    val sourceName = when (forecast.providerId) {
        "met_norway" -> "Yr"
        "met_office" -> "Met Office"
        else -> forecast.providerName
    }
    val source = if (item.usingFallback) " · $sourceName fallback" else ""
    return (if (item.lastRefreshFailed) "Update failed · data $updated"
        else if (!isFresh(forecast.fetchedAt, now, java.time.Duration.ofHours(6))) "Old data · $updated"
        else "Updated $updated") + source
}

@Composable
private fun OpenLocationsText(text: String, context: Context) {
    Text(text, modifier = GlanceModifier.fillMaxWidth().clickable(openAppAction(context, chooseLocations = true)).padding(4.dp),
        style = TextStyle(fontSize = 14.sp, color = cyan))
}

internal fun openAppAction(context: Context, locationId: Long = 0, chooseLocations: Boolean = false) =
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setAction(if (chooseLocations) "com.example.weatherapp.CHOOSE_LOCATIONS" else "com.example.weatherapp.OPEN_LOCATION_$locationId")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_LOCATION_ID, locationId)
            .putExtra(MainActivity.EXTRA_LOCATIONS_PAGE, chooseLocations)
    )

private fun Double?.temp(): String = this?.takeIf { it.isFinite() }?.let { it.roundToInt().toString() + "°" } ?: "—"

suspend fun updateWeatherWidgets(context: Context) {
    (context.applicationContext as WeatherApplication).container.widgetTime.value = Instant.now()
    WeatherWidget().updateAll(context)
}
