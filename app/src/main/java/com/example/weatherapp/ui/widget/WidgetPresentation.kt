package com.example.weatherapp.ui.widget

import com.example.weatherapp.data.repository.LocationForecast
import com.example.weatherapp.data.repository.isFresh
import com.example.weatherapp.domain.mapper.WeatherCodeMapper
import com.example.weatherapp.domain.model.zone
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal data class WidgetCellContent(
    val label: String,
    val shortLabel: String,
    val code: Int?,
    val temperature: String,
    val description: String
) {
    fun heading(abbreviated: Boolean) = if (abbreviated) shortLabel else label
}

internal data class WidgetLocationContent(
    val id: Long,
    val name: String,
    val status: String,
    val description: String,
    val cells: List<WidgetCellContent>
)

internal fun widgetLocationContent(item: LocationForecast, active: Boolean, now: Instant, refreshProblem: String? = null): WidgetLocationContent {
    val zone = item.location.zone()
    val today = now.atZone(zone).toLocalDate()
    val current = item.forecast?.current
    val conditionsTime = current?.time?.withZoneSameInstant(zone)
    val currentLabel = conditionsTime?.format(DateTimeFormatter.ofPattern(if (conditionsTime.toLocalDate() == today) "HH:mm" else "d/M HH:mm")) ?: "Now"
    val cells = listOf(WidgetCellContent(currentLabel, currentLabel, current?.weatherCode, current?.temperature.temp(),
        if (current == null) "Current conditions unavailable" else "Conditions at $conditionsTime. ${current.temperature.temp()}. ${WeatherCodeMapper.condition(current.weatherCode ?: -1).label}")) +
        (0..2).map { offset ->
            val date = today.plusDays(offset.toLong())
            val day = item.forecast?.daily?.find { it.date == date }
            val temperature = day?.let { it.tempMax.temp() + "/" + it.tempMin.temp() } ?: "—"
            WidgetCellContent(if (offset == 0) "" else date.format(DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH)),
                if (offset == 0) "" else date.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)),
                day?.weatherCode, temperature, "$date. High/low $temperature. ${WeatherCodeMapper.condition(day?.weatherCode ?: -1).label}")
        }
    return WidgetLocationContent(item.location.id, item.location.name, compactWidgetStatus(item, active, now, refreshProblem),
        "${item.location.name}. ${widgetStatus(item, active, now, refreshProblem)}", cells)
}

internal fun compactWidgetStatus(item: LocationForecast, active: Boolean, now: Instant, refreshProblem: String? = null): String {
    if (active) return "Updating…"
    val failed = item.lastRefreshFailed || refreshProblem != null
    val forecast = item.forecast ?: return if (failed) "Failed" else "Unavailable"
    val zone = item.location.zone()
    val fetched = forecast.fetchedAt.atZone(zone)
    val time = fetched.format(DateTimeFormatter.ofPattern(if (fetched.toLocalDate() == now.atZone(zone).toLocalDate()) "HH:mm" else "d/M HH:mm"))
    return when {
        failed -> "Failed · $time"
        !isFresh(forecast.fetchedAt, now, Duration.ofHours(6)) -> "Old · $time"
        item.usingFallback -> "${sourceName(item)} fallback · $time"
        else -> "Updated $time"
    }
}

internal fun widgetStatus(item: LocationForecast, active: Boolean, now: Instant, refreshProblem: String? = null): String {
    val state = compactWidgetStatus(item, active, now, refreshProblem)
    val forecast = item.forecast
    val source = forecast?.let { "Source ${it.providerName}${if (item.usingFallback) " fallback" else ""}. Downloaded ${it.fetchedAt.atZone(item.location.zone())}." }
        ?: "Forecast unavailable."
    val failure = refreshProblem ?: if (item.lastRefreshFailed) "Last update failed." else null
    return listOfNotNull(state, source, failure, if (item.usingFallback) item.preferredSourceError else null, "Tap to open forecast.").joinToString(" ")
}

private fun sourceName(item: LocationForecast): String = when (item.forecast?.providerId) {
    "met_norway" -> "Yr"
    "met_office" -> "Met Office"
    else -> item.forecast?.providerName.orEmpty()
}

private fun Double?.temp(): String = this?.takeIf { it.isFinite() }?.let { it.roundToInt().toString() + "°" } ?: "—"
