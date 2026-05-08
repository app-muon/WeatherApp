package com.example.weatherapp.ui.forecast

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.domain.mapper.WeatherCodeMapper
import com.example.weatherapp.domain.model.DailyForecast
import com.example.weatherapp.domain.model.Forecast
import com.example.weatherapp.domain.model.HourlyForecast
import com.example.weatherapp.domain.model.MarineConditions
import com.example.weatherapp.settings.WeatherSettings
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun ForecastScreen(
    state: ForecastUiState,
    onSelectLocation: (Long) -> Unit,
    onRefresh: () -> Unit,
    onExpandDay: (LocalDate) -> Unit
) {
    val selected = state.selected
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            state.items.forEach { item ->
                val isSelected = item.location.id == selected?.location?.id
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectLocation(item.location.id) },
                    label = { Text(item.location.name) },
                    colors = selectedChipColors(),
                    border = selectedChipBorder(isSelected)
                )
            }
        }
        if (selected == null) {
            Text("Add a location to see the forecast.")
            return@Column
        }

        if (selected.forecast == null) {
            ErrorState(onRefresh)
        } else {
            CurrentWeatherPanel(selected.location, selected.forecast, state.marineConditions, state.settings)
            HourlyForecastSection(selected.forecast.hourly, state.settings)
            DailyForecastSection(
                forecast = selected.forecast,
                settings = state.settings,
                expandedDay = state.expandedDay,
                onExpandDay = onExpandDay
            )
        }
    }
}

@Composable
fun WidgetSelectionSection(
    state: ForecastUiState,
    onSetWidgetLocation: (Long, Int) -> Unit
) {
    Card {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Widget locations", style = MaterialTheme.typography.titleMedium)
            repeat(2) { slot ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Row ${slot + 1}", style = MaterialTheme.typography.labelLarge)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        state.items.forEach { item ->
                            val isSelected = item.location.widgetOrder == slot
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSetWidgetLocation(item.location.id, slot) },
                                label = { Text(item.location.name) },
                                colors = selectedChipColors(),
                                border = selectedChipBorder(isSelected)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorState(onRefresh: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Could not load forecast", style = MaterialTheme.typography.titleMedium)
            Text("Check your connection and retry.")
            Button(onClick = onRefresh) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun CurrentWeatherPanel(
    location: LocationEntity,
    forecast: Forecast,
    marineConditions: MarineConditions?,
    settings: WeatherSettings
) {
    val current = forecast.current
    val condition = WeatherCodeMapper.condition(current.weatherCode)
    val currentHour = forecast.hourly.minByOrNull {
        kotlin.math.abs(Duration.between(current.time, it.time).toMinutes())
    }

    ElevatedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    painter = painterResource(WeatherCodeMapper.drawableRes(current.weatherCode)),
                    contentDescription = condition.label,
                    modifier = Modifier.size(42.dp)
                )
                Column {
                    Text(location.name, style = MaterialTheme.typography.headlineSmall)
                    Text("${current.temperature.temp(settings)} - ${condition.label}", style = MaterialTheme.typography.titleMedium)
                }
            }
            DetailGrid(
                listOf(
                    "Feels like" to current.feelsLike.temp(settings),
                    "Humidity" to "${current.humidity}%",
                    "Wind" to "${current.windSpeed.oneDecimal()} ${settings.windUnitLabel()} ${current.windDirection.compass()}",
                    "Rain now" to "${current.rain.oneDecimal()} ${settings.precipitationUnitLabel()}",
                    "Precip chance" to (currentHour?.precipitationProbability?.let { "$it%" } ?: "Unavailable"),
                    "Pressure" to "${current.pressure.roundToInt()} hPa",
                    "Cloud cover" to "${current.cloudCover}%",
                    "UV index" to (currentHour?.uvIndex?.oneDecimal() ?: "Unavailable"),
                    "Last updated" to forecast.fetchedAt.atZone(current.time.zone).format(DateTimeFormatter.ofPattern("HH:mm"))
                )
            )
            MarineConditionsSection(marineConditions)
        }
    }
}

@Composable
private fun MarineConditionsSection(marine: MarineConditions?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text("Marine", style = MaterialTheme.typography.titleMedium)
        if (marine == null || !marine.hasAvailableData()) {
            Text("Marine unavailable", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        DetailGrid(
            listOf<Pair<String, String>>(
                Pair("Sea temperature", marine.seaSurfaceTemperature?.let { "${it.oneDecimal()}\u00B0C" } ?: "Unavailable"),
                Pair("Wave height", marine.waveHeight?.let { "${it.oneDecimal()} m" } ?: "Unavailable"),
                Pair("Wave period", marine.wavePeriod?.let { "${it.oneDecimal()} s" } ?: "Unavailable"),
                Pair(
                    "Wave direction",
                    marine.waveDirection?.let { "${it}\u00B0 ${it.waveCompass()}" } ?: "Unavailable"
                )
            )
        )
    }
}

@Composable
private fun DetailGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (label, value) ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(10.dp)
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HourlyForecastSection(hourly: List<HourlyForecast>, settings: WeatherSettings) {
    val now = ZonedDateTime.now(hourly.firstOrNull()?.time?.zone)
    val next48 = hourly.filter { !it.time.isBefore(now.minusHours(1)) }.take(48)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Hourly", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            next48.forEach { item ->
                HourlyCard(item, settings)
            }
        }
    }
}

@Composable
private fun HourlyCard(item: HourlyForecast, settings: WeatherSettings) {
    Card(modifier = Modifier.width(112.dp)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(item.time.format(DateTimeFormatter.ofPattern("EEE HH:mm")), style = MaterialTheme.typography.labelMedium)
            Image(
                painter = painterResource(WeatherCodeMapper.drawableRes(item.weatherCode)),
                contentDescription = WeatherCodeMapper.condition(item.weatherCode).label,
                modifier = Modifier.size(28.dp)
            )
            Text(item.temperature.temp(settings), style = MaterialTheme.typography.titleMedium)
            Text("Feels ${item.feelsLike.temp(settings)}", style = MaterialTheme.typography.bodySmall)
            Text("Rain ${item.precipitationProbability?.let { "$it%" } ?: "-"}", style = MaterialTheme.typography.bodySmall)
            Text("Wind ${item.windSpeed.roundToInt()} ${settings.windUnitLabel()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DailyForecastSection(
    forecast: Forecast,
    settings: WeatherSettings,
    expandedDay: LocalDate?,
    onExpandDay: (LocalDate) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Daily", style = MaterialTheme.typography.titleLarge)
        forecast.daily.take(7).forEach { day ->
            DailyCard(
                day = day,
                hourly = forecast.hourly.filter { it.time.toLocalDate() == day.date },
                settings = settings,
                expanded = expandedDay == day.date,
                onClick = { onExpandDay(day.date) }
            )
        }
    }
}

@Composable
private fun DailyCard(
    day: DailyForecast,
    hourly: List<HourlyForecast>,
    settings: WeatherSettings,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(
                    painter = painterResource(WeatherCodeMapper.drawableRes(day.weatherCode)),
                    contentDescription = WeatherCodeMapper.condition(day.weatherCode).label,
                    modifier = Modifier.size(24.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(day.date.format(DateTimeFormatter.ofPattern("EEE d MMM")), style = MaterialTheme.typography.titleMedium)
                    Text(WeatherCodeMapper.condition(day.weatherCode).label, style = MaterialTheme.typography.bodySmall)
                }
                Text("${day.tempMin.temp(settings)} / ${day.tempMax.temp(settings)}", style = MaterialTheme.typography.titleMedium)
            }
            if (expanded) {
                DetailGrid(
                    listOf(
                        "Precip max" to (day.precipitationProbabilityMax?.let { "$it%" } ?: "Unavailable"),
                        "Rain total" to "${day.rainSum.oneDecimal()} ${settings.precipitationUnitLabel()}",
                        "Max wind" to "${day.windSpeedMax.roundToInt()} ${settings.windUnitLabel()}",
                        "UV max" to (day.uvIndexMax?.oneDecimal() ?: "Unavailable"),
                        "Sunrise" to (day.sunrise?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "Unavailable"),
                        "Sunset" to (day.sunset?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "Unavailable")
                    )
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    hourly.forEach { HourlyCard(it, settings) }
                }
            }
        }
    }
}

private fun Double.temp(settings: WeatherSettings): String = "${roundToInt()}\u00B0${settings.temperatureSuffix()}"
private fun Double.oneDecimal(): String = "%.1f".format(this)
private fun WeatherSettings.temperatureSuffix(): String =
    if (temperatureUnit == "fahrenheit") "F" else "C"
private fun WeatherSettings.windUnitLabel(): String =
    if (windSpeedUnit == "mph") "mph" else "km/h"
private fun WeatherSettings.precipitationUnitLabel(): String =
    if (precipitationUnit == "inch") "in" else "mm"
private fun Int.compass(): String {
    val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return directions[((this + 22.5) / 45).toInt() % 8]
}

private fun Int.waveCompass(): String = when (((this % 360) + 360) % 360) {
    in 338..359, in 0..22 -> "N"
    in 23..67 -> "NE"
    in 68..112 -> "E"
    in 113..157 -> "SE"
    in 158..202 -> "S"
    in 203..247 -> "SW"
    in 248..292 -> "W"
    else -> "NW"
}

private fun MarineConditions.hasAvailableData(): Boolean =
    seaSurfaceTemperature != null || waveHeight != null || waveDirection != null || wavePeriod != null

@Composable
private fun selectedChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    containerColor = MaterialTheme.colorScheme.surfaceVariant,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
private fun selectedChipBorder(isSelected: Boolean) = FilterChipDefaults.filterChipBorder(
    enabled = true,
    selected = isSelected,
    borderColor = MaterialTheme.colorScheme.surfaceVariant,
    selectedBorderColor = MaterialTheme.colorScheme.primary,
    borderWidth = 1.dp,
    selectedBorderWidth = 2.dp
)
