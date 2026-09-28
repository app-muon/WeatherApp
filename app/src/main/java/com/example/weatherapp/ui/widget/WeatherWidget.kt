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
import androidx.glance.semantics.semantics
import androidx.glance.semantics.contentDescription
import androidx.work.WorkManager
import com.example.weatherapp.MainActivity
import com.example.weatherapp.WeatherApplication
import com.example.weatherapp.data.repository.*
import com.example.weatherapp.domain.mapper.WeatherCodeMapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

private val cyan = 0xFF00E5FF.toInt()
private val primary = 0xFFE8F7FF.toInt()
private val secondary = 0xFFC5D8DE.toInt()

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
    val rows = items.take(2).map { item ->
        widgetLocationContent(item, widgetIsUpdating(item, activity), now, activity.failure(item.location, ContentScope.Forecast))
    }
    WeatherWidgetRows(rows, layoutMetrics)
}

@Composable
internal fun WeatherWidgetRows(rows: List<WidgetLocationContent>, layoutMetrics: WidgetLayoutMetrics) {
    val context = LocalContext.current
    val size = LocalSize.current
    val layout = chooseWidgetLayout(size.width.value, size.height.value, layoutMetrics, rows)
    Box(GlanceModifier.fillMaxSize().background(Color(0xFF00E5FF)).padding(2.dp)) {
        Box(GlanceModifier.fillMaxSize().background(Color(0xFFFF3DF2)).padding(1.dp)) {
            Column(GlanceModifier.fillMaxSize().background(Color(0xFF050711))
                .padding(horizontal = layout.horizontalPadding.dp, vertical = layout.innerPadding.dp),
                // Centre any pixels left after Glance rounds padding and row dimensions.
                verticalAlignment = Alignment.Vertical.CenterVertically) {
                if (rows.isEmpty()) {
                    OpenLocationsText("Choose locations", context, layout, layoutMetrics)
                } else {
                    rows.take(layout.visibleLocations).forEachIndexed { index, row ->
                        if (index > 0) Spacer(GlanceModifier.height(layout.rowGap.dp))
                        WeatherWidgetRow(row, context, layout, layoutMetrics, hidden = rows.size - layout.visibleLocations)
                    }
                    if (layout.addLocation == AddLocationAction.Label) {
                        Spacer(GlanceModifier.height(layout.rowGap.dp))
                        OpenLocationsText("Add second location", context, layout, layoutMetrics)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherWidgetRow(
    item: WidgetLocationContent, context: Context, layout: WidgetLayoutSpec, metrics: WidgetLayoutMetrics, hidden: Int
) {
    val type = layout.typography
    val suffix = if (hidden > 0) " (+$hidden)" else ""
    val plusWidth = if (layout.addLocation == AddLocationAction.Plus) maxOf(20f, metrics.measure("+", type.name).width + 8) else 0f
    val suffixWidth = metrics.measure(suffix, type.name).width
    val width = layout.columnWidths.sum()
    val inlineValueWidth = if (layout.currentInHeader) type.iconSize + metrics.measure(item.cells.first().temperature, type.value).width + 4 else 0f
    val statusWidth = minOf(metrics.measure(item.status, type.status).width,
        (width - plusWidth - suffixWidth - inlineValueWidth - metrics.measure("…", type.name).width - 4).coerceAtLeast(0f))
    Column(GlanceModifier.fillMaxWidth()) {
        Row(GlanceModifier.fillMaxWidth().height(layout.headerHeight.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Row(GlanceModifier.defaultWeight().clickable(openAppAction(context, item.id))
                .semantics { contentDescription = item.description + if (hidden > 0) " $hidden more location hidden." else "" },
                verticalAlignment = Alignment.Vertical.CenterVertically) {
                WidgetText(item.name, type.name, metrics, cyan, GlanceModifier.defaultWeight(),
                    description = item.description, height = layout.headerHeight)
                if (suffix.isNotEmpty()) WidgetText(suffix, type.name, metrics, cyan, GlanceModifier.width(suffixWidth.dp), height = layout.headerHeight)
                Spacer(GlanceModifier.width(4.dp))
                if (layout.currentInHeader) {
                    WeatherValue(item.cells.first(), layout, metrics)
                    Spacer(GlanceModifier.width(4.dp))
                }
                WidgetText(item.status, type.status, metrics, secondary, GlanceModifier.width(statusWidth.dp),
                    alignEnd = true, description = item.description, height = layout.headerHeight)
            }
            if (layout.addLocation == AddLocationAction.Plus) {
                WidgetText("+", type.name, metrics, cyan,
                    GlanceModifier.width(plusWidth.dp).clickable(openAppAction(context, chooseLocations = true)),
                    alignEnd = true, description = "Add second location", height = layout.headerHeight)
            }
        }
        if (!layout.currentInHeader) {
            Row(GlanceModifier.fillMaxWidth().clickable(openAppAction(context, item.id))) {
                (if (layout.showForecast) item.cells else item.cells.take(1)).forEachIndexed { index, cell ->
                    WeatherCell(cell, layout, metrics, GlanceModifier.width(layout.columnWidths[index].dp))
                }
            }
        }
    }
}

@Composable
private fun WeatherCell(cell: WidgetCellContent, layout: WidgetLayoutSpec, metrics: WidgetLayoutMetrics, modifier: GlanceModifier) {
    val type = layout.typography
    val heading = cell.heading(layout.abbreviatedDays)
    if (layout.inlineHeadings) {
        Row(modifier.padding(end = layout.columnGap.dp).height(layout.valueHeight.dp)
            .semantics { contentDescription = cell.description }, verticalAlignment = Alignment.Vertical.CenterVertically) {
            if (heading.isNotEmpty()) {
                WidgetText(heading, type.date, metrics, secondary,
                    GlanceModifier.width(metrics.measure(heading, type.date).width.dp), height = layout.valueHeight)
                Spacer(GlanceModifier.width(2.dp))
            }
            WeatherValue(cell, layout, metrics)
        }
        return
    }
    Column(modifier.padding(end = layout.columnGap.dp).semantics { contentDescription = cell.description }) {
        if (layout.dateHeight > 0) {
            if (heading.isEmpty()) Spacer(GlanceModifier.height(layout.dateHeight.dp))
            else WidgetText(heading, type.date, metrics, secondary,
                GlanceModifier.fillMaxWidth(), height = layout.dateHeight)
        }
        WeatherValue(cell, layout, metrics)
    }
}

@Composable
private fun WeatherValue(cell: WidgetCellContent, layout: WidgetLayoutSpec, metrics: WidgetLayoutMetrics) {
    val type = layout.typography
    Row(GlanceModifier.height(layout.valueHeight.dp).semantics { contentDescription = cell.description },
        verticalAlignment = Alignment.Vertical.CenterVertically) {
        Image(ImageProvider(WeatherCodeMapper.drawableRes(cell.code ?: -1)),
            contentDescription = WeatherCodeMapper.condition(cell.code ?: -1).label,
            modifier = GlanceModifier.size(type.iconSize.dp))
        WidgetText(cell.temperature, type.value, metrics, primary,
            GlanceModifier.width(metrics.measure(cell.temperature, type.value).width.dp), height = layout.valueHeight)
    }
}

@Composable
private fun OpenLocationsText(text: String, context: Context, layout: WidgetLayoutSpec, metrics: WidgetLayoutMetrics) {
    WidgetText(text, layout.typography.action, metrics, cyan,
        GlanceModifier.fillMaxWidth().clickable(openAppAction(context, chooseLocations = true)))
}

internal fun openAppAction(context: Context, locationId: Long = 0, chooseLocations: Boolean = false) =
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setAction(if (chooseLocations) "com.example.weatherapp.CHOOSE_LOCATIONS" else "com.example.weatherapp.OPEN_LOCATION_$locationId")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_LOCATION_ID, locationId)
            .putExtra(MainActivity.EXTRA_LOCATIONS_PAGE, chooseLocations)
    )

suspend fun updateWeatherWidgets(context: Context) {
    (context.applicationContext as WeatherApplication).container.widgetTime.value = Instant.now()
    WeatherWidget().updateAll(context)
}
