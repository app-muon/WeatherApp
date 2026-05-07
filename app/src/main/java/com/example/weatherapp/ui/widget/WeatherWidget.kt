package com.example.weatherapp.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.WorkManager
import com.example.weatherapp.MainActivity
import com.example.weatherapp.WeatherApplication
import com.example.weatherapp.data.repository.LocationForecast
import com.example.weatherapp.data.repository.WeatherRepository
import com.example.weatherapp.domain.mapper.WeatherCodeMapper
import com.example.weatherapp.domain.model.DailyForecast
import com.example.weatherapp.domain.model.Forecast
import java.time.Instant
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class WeatherWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as WeatherApplication).container
        val forecasts = container.weatherRepository.getCachedWidgetForecasts()
        WeatherRepository.enqueueRefreshIfStale(WorkManager.getInstance(context), forecasts)
        provideContent {
            WeatherWidgetContent(forecasts)
        }
    }
}

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()
}

@Composable
private fun WeatherWidgetContent(items: List<LocationForecast>) {
    val context = LocalContext.current
    val widgetSize = LocalSize.current
    val rowHeight = ((widgetSize.height - 14.dp) / 2).coerceIn(34.dp, 64.dp)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0xFF00E5FF)))
            .padding(2.dp)
            .background(ColorProvider(Color(0xFFFF3DF2)))
            .padding(1.dp)
            .background(ColorProvider(Color(0xFF050711)))
            .padding(5.dp),
        verticalAlignment = Alignment.Vertical.Top
    ) {
        when (items.size) {
            0 -> EmptyWidgetText("Tap to choose locations", context, 0)
            1 -> {
                WeatherWidgetRow(items[0], context, rowHeight)
                Spacer(GlanceModifier.height(2.dp))
                EmptyWidgetText("Add second location", context, items[0].location.id, rowHeight)
            }
            else -> {
                WeatherWidgetRow(items[0], context, rowHeight)
                Spacer(GlanceModifier.height(2.dp))
                WeatherWidgetRow(items[1], context, rowHeight)
            }
        }
    }
}

@Composable
private fun WeatherWidgetRow(item: LocationForecast, context: Context, rowHeight: androidx.compose.ui.unit.Dp) {
    val forecast = item.forecast
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(rowHeight)
            .clickable(openAppAction(context, item.location.id)),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        Text(
            text = item.location.name.take(16),
            modifier = GlanceModifier.width(96.dp),
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = ColorProvider(Color(0xFF00E5FF)))
        )
        if (forecast == null) {
            Text("No cache", style = TextStyle(fontSize = 14.sp, color = ColorProvider(Color(0xFFC5D8DE))))
        } else {
            CurrentCell(forecast)
            forecast.daily.take(3).forEach { day ->
                DailyCell(day)
            }
            if (isStale(forecast)) {
                Text("\u21BB", style = TextStyle(fontSize = 14.sp, color = ColorProvider(Color(0xFFE8FF4A))))
            }
        }
    }
}

@Composable
private fun CurrentCell(forecast: Forecast) {
    Row(
        modifier = GlanceModifier.width(58.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        WeatherImage(forecast.current.weatherCode)
        Text(
            text = forecast.current.temperature.temp(),
            style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, color = ColorProvider(Color(0xFFE8F7FF)))
        )
    }
}

@Composable
private fun DailyCell(day: DailyForecast) {
    Row(
        modifier = GlanceModifier.width(66.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        WeatherImage(day.weatherCode)
        Text(
            text = "${day.tempMin.temp()}/${day.tempMax.temp()}",
            style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, color = ColorProvider(Color(0xFFE8F7FF)))
        )
    }
}

@Composable
private fun WeatherImage(code: Int) {
    Image(
        provider = ImageProvider(WeatherCodeMapper.drawableRes(code)),
        contentDescription = WeatherCodeMapper.condition(code).label,
        modifier = GlanceModifier.size(22.dp)
    )
}

@Composable
private fun EmptyWidgetText(
    text: String,
    context: Context,
    locationId: Long,
    rowHeight: androidx.compose.ui.unit.Dp = 42.dp
) {
    Text(
        text = text,
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(rowHeight)
            .clickable(openAppAction(context, locationId))
            .padding(4.dp),
        style = TextStyle(fontSize = 14.sp, color = ColorProvider(Color(0xFF00E5FF)))
    )
}

private fun openAppAction(context: Context, locationId: Long) =
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setAction("com.example.weatherapp.OPEN_LOCATION_$locationId")
            .putExtra(MainActivity.EXTRA_LOCATION_ID, locationId)
    )

private fun isStale(forecast: Forecast): Boolean =
    Instant.now().toEpochMilli() - forecast.fetchedAt.toEpochMilli() > WeatherRepository.STALE_AFTER_MILLIS

private fun Double.temp(): String = "${roundToInt()}\u00B0"

suspend fun updateWeatherWidgets(context: Context) {
    WeatherWidget().updateAll(context)
}
