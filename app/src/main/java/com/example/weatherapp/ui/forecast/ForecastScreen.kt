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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.data.provider.WeatherProviderIds
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

private enum class ForecastSubTab(val label: String) {
    Forecast("Forecast"),
    Compare("Compare"),
    Marine("Marine")
}

@Composable
fun ForecastScreen(
    state: ForecastUiState,
    onSelectLocation: (Long) -> Unit,
    onSelectComparisonDay: (Int) -> Unit,
    onOpenCompare: () -> Unit,
    onRefresh: () -> Unit,
    onExpandDay: (LocalDate) -> Unit
) {
    val selected = state.selected
    val detailForecast = state.selectedForecast
    var selectedTab by remember { mutableStateOf(ForecastSubTab.Forecast) }

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

        TabRow(selectedTabIndex = selectedTab.ordinal) {
            ForecastSubTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = {
                        selectedTab = tab
                        if (tab == ForecastSubTab.Compare) onOpenCompare()
                    },
                    text = { Text(tab.label) }
                )
            }
        }

        if (selected == null) {
            Text("Add a location to see the forecast.")
            return@Column
        }

        if (detailForecast == null) {
            ErrorState(onRefresh)
            return@Column
        }

        when (selectedTab) {
            ForecastSubTab.Forecast -> {
                Text(
                    text = detailForecast.providerName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CurrentWeatherPanel(selected.location, detailForecast, state.settings)
                HourlyForecastSection(detailForecast.hourly, state.settings)
                DailyForecastSection(
                    forecast = detailForecast,
                    settings = state.settings,
                    expandedDay = state.expandedDay,
                    onExpandDay = onExpandDay
                )
            }
            ForecastSubTab.Compare -> {
                ForecastComparisonSection(
                    forecasts = selected.providerForecasts,
                    settings = state.settings,
                    selectedOffset = state.comparisonDayOffset,
                    onSelectOffset = onSelectComparisonDay
                )
                if (state.comparisonDayOffset == -1) {
                    HourlyComparisonSection(selected.providerForecasts, state.settings)
                }
            }
            ForecastSubTab.Marine -> {
                MarineSection(state.marineConditions)
            }
        }
    }
}

@Composable
fun WidgetSelectionSection(
    state: ForecastUiState,
    onSetWidgetLocation: (Long, Int) -> Unit,
    onSetWidgetSource: (Long, String) -> Unit
) {
    Card {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Widget", style = MaterialTheme.typography.titleMedium)
            repeat(2) { slot ->
                val selectedItem = state.items.firstOrNull { it.location.widgetOrder == slot }
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
                    if (selectedItem != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            val selectedProvider = selectedItem.widgetSourcePreference?.selectedProviderId ?: WeatherProviderIds.AUTO
                            state.providerOptions[selectedItem.location.id].orEmpty().forEach { option ->
                                val isSelected = selectedProvider == option.id
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSetWidgetSource(selectedItem.location.id, option.id) },
                                    label = { Text(option.displayName) },
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
}

@Composable
fun ForecastSourceSection(
    state: ForecastUiState,
    onSetForecastSource: (Long, String) -> Unit
) {
    Card {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Default source", style = MaterialTheme.typography.titleMedium)
            state.items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.location.name, style = MaterialTheme.typography.labelLarge)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        val selectedProvider = item.forecastSourcePreference?.selectedProviderId ?: WeatherProviderIds.AUTO
                        state.providerOptions[item.location.id].orEmpty()
                            .filter { it.id != WeatherProviderIds.AUTO }
                            .forEach { option ->
                            val isSelected = selectedProvider == option.id
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSetForecastSource(item.location.id, option.id) },
                                label = { Text(option.displayName) },
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
private fun ForecastComparisonSection(
    forecasts: List<Forecast>,
    settings: WeatherSettings,
    selectedOffset: Int,
    onSelectOffset: (Int) -> Unit
) {
    val tabs = listOf(0 to "Today", 1 to "Tomorrow", 2 to "T+2", 7 to "7 days", -1 to "Hourly")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Source comparison", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            tabs.forEach { (offset, label) ->
                val isSelected = selectedOffset == offset
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectOffset(offset) },
                    label = { Text(label) },
                    colors = selectedChipColors(),
                    border = selectedChipBorder(isSelected)
                )
            }
        }
        when (selectedOffset) {
            7 -> SevenDayComparison(forecasts, settings)
            -1 -> { /* HourlyComparisonSection rendered below in caller */ }
            else -> DayComparisonTable(forecasts, settings, selectedOffset)
        }
    }
}

@Composable
private fun DayComparisonTable(forecasts: List<Forecast>, settings: WeatherSettings, offset: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ComparisonHeader()
        forecasts.forEach { forecast ->
            val day = forecast.daily.getOrNull(offset)
            ComparisonRow(
                source = forecast.providerName,
                weatherCode = day?.weatherCode,
                minMax = day?.minMax(settings) ?: "\u2014",
                rain = day?.precipitationProbabilityMax?.let { "$it%" } ?: "\u2014",
                wind = day?.windSpeedMax?.let { "${it.roundToInt()} ${settings.windUnitLabel()}" } ?: "\u2014"
            )
        }
    }
}

@Composable
private fun SevenDayComparison(forecasts: List<Forecast>, settings: WeatherSettings) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        forecasts.forEach { forecast ->
            Text(forecast.providerName, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                forecast.daily.take(7).forEach { day ->
                    Card(modifier = Modifier.width(96.dp)) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(day.date.format(DateTimeFormatter.ofPattern("EEE")), style = MaterialTheme.typography.labelMedium)
                            Image(
                                painter = painterResource(WeatherCodeMapper.drawableRes(day.weatherCode ?: 0)),
                                contentDescription = WeatherCodeMapper.condition(day.weatherCode ?: 0).label,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(day.minMax(settings), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonHeader() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Source", modifier = Modifier.weight(1.4f), style = MaterialTheme.typography.labelMedium)
        Text("Icon", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.labelMedium)
        Text("Min/Max", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        Text("Rain", modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.labelMedium)
        Text("Wind", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ComparisonRow(source: String, weatherCode: Int?, minMax: String, rain: String, wind: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(8.dp)
    ) {
        Text(source, modifier = Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
        Image(
            painter = painterResource(WeatherCodeMapper.drawableRes(weatherCode ?: 0)),
            contentDescription = WeatherCodeMapper.condition(weatherCode ?: 0).label,
            modifier = Modifier
                .weight(0.5f)
                .size(24.dp)
        )
        Text(minMax, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(rain, modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.bodyMedium)
        Text(wind, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HourlyComparisonSection(forecasts: List<Forecast>, settings: WeatherSettings) {
    val allTimes = forecasts.flatMap { it.hourly.map { hour -> hour.time } }
        .distinct()
        .sorted()
        .take(24)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Hourly", style = MaterialTheme.typography.titleLarge)
        allTimes.forEach { time ->
            Card {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(time.format(DateTimeFormatter.ofPattern("EEE HH:mm")), style = MaterialTheme.typography.titleMedium)
                    forecasts.forEach { forecast ->
                        val hour = forecast.hourly.firstOrNull { it.time == time }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(forecast.providerName, modifier = Modifier.weight(1.2f))
                            Text(hour?.temperature.temp(settings), modifier = Modifier.weight(0.7f))
                            Text("Rain ${hour?.precipitationProbability?.let { "$it%" } ?: "\u2014"}", modifier = Modifier.weight(1f))
                            Text("Wind ${hour?.windSpeed?.let { "${it.roundToInt()} ${settings.windUnitLabel()}" } ?: "\u2014"}", modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentWeatherPanel(
    location: LocationEntity,
    forecast: Forecast,
    settings: WeatherSettings
) {
    val current = forecast.current
    val currentCode = current?.weatherCode ?: 0
    val condition = WeatherCodeMapper.condition(currentCode)
    val currentHour = forecast.hourly.minByOrNull {
        kotlin.math.abs(Duration.between(current?.time ?: it.time, it.time).toMinutes())
    }

    ElevatedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    painter = painterResource(WeatherCodeMapper.drawableRes(currentCode)),
                    contentDescription = condition.label,
                    modifier = Modifier.size(42.dp)
                )
                Column {
                    Text(location.name, style = MaterialTheme.typography.headlineSmall)
                    Text("${current?.temperature.temp(settings)} - ${condition.label}", style = MaterialTheme.typography.titleMedium)
                }
            }
            DetailGrid(
                listOf(
                    "Feels like" to current?.feelsLike.temp(settings),
                    "Humidity" to (current?.humidity?.let { "$it%" } ?: "\u2014"),
                    "Wind" to current?.windSpeed.wind(settings, current?.windDirection),
                    "Rain now" to (current?.rain?.let { "${it.oneDecimal()} ${settings.precipitationUnitLabel()}" } ?: "\u2014"),
                    "Precip chance" to (currentHour?.precipitationProbability?.let { "$it%" } ?: "—"),
                    "Pressure" to (current?.pressure?.let { "${it.roundToInt()} hPa" } ?: "\u2014"),
                    "Cloud cover" to (current?.cloudCover?.let { "$it%" } ?: "\u2014"),
                    "UV index" to (currentHour?.uvIndex?.oneDecimal() ?: "—"),
                    "Last updated" to forecast.fetchedAt.atZone((current?.time ?: ZonedDateTime.now()).zone).format(DateTimeFormatter.ofPattern("HH:mm"))
                )
            )
        }
    }
}

@Composable
private fun MarineSection(marine: MarineConditions?) {
    ElevatedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Marine", style = MaterialTheme.typography.titleLarge)
            if (marine == null || !marine.hasAvailableData()) {
                Text("Marine unavailable", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            DetailGrid(
                listOf(
                    "Sea temperature" to (marine.seaSurfaceTemperature?.let { "${it.oneDecimal()}\u00B0C — ${it.seaTempLabel()}" } ?: "—"),
                    "Wave height" to (marine.waveHeight?.let { "${it.oneDecimal()} m" } ?: "—"),
                    "Wave period" to (marine.wavePeriod?.let { "${it.oneDecimal()} s" } ?: "—"),
                    "Wave direction" to (marine.waveDirection?.let { "${it}\u00B0 ${it.waveCompass()}" } ?: "—")
                )
            )
        }
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Hourly", style = MaterialTheme.typography.titleLarge)
        if (hourly.isEmpty()) {
            Text("—", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val now = ZonedDateTime.now(hourly.first().time.zone)
        val next48 = hourly.filter { !it.time.isBefore(now.minusHours(1)) }.take(48)
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
                painter = painterResource(WeatherCodeMapper.drawableRes(item.weatherCode ?: 0)),
                contentDescription = WeatherCodeMapper.condition(item.weatherCode ?: 0).label,
                modifier = Modifier.size(28.dp)
            )
            Text(item.temperature.temp(settings), style = MaterialTheme.typography.titleMedium)
            Text("Feels ${item.feelsLike.temp(settings)}", style = MaterialTheme.typography.bodySmall)
            Text("Rain ${item.precipitationProbability?.let { "$it%" } ?: "\u2014"}", style = MaterialTheme.typography.bodySmall)
            Text("Wind ${item.windSpeed?.roundToInt()?.let { "$it ${settings.windUnitLabel()}" } ?: "\u2014"}", style = MaterialTheme.typography.bodySmall)
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
                    painter = painterResource(WeatherCodeMapper.drawableRes(day.weatherCode ?: 0)),
                    contentDescription = WeatherCodeMapper.condition(day.weatherCode ?: 0).label,
                    modifier = Modifier.size(24.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(day.date.format(DateTimeFormatter.ofPattern("EEE d MMM")), style = MaterialTheme.typography.titleMedium)
                    Text(WeatherCodeMapper.condition(day.weatherCode ?: 0).label, style = MaterialTheme.typography.bodySmall)
                }
                Text(day.minMax(settings), style = MaterialTheme.typography.titleMedium)
            }
            if (expanded) {
                DetailGrid(
                    listOf(
                        "Precip max" to (day.precipitationProbabilityMax?.let { "$it%" } ?: "—"),
                        "Rain total" to (day.rainSum?.let { "${it.oneDecimal()} ${settings.precipitationUnitLabel()}" } ?: "\u2014"),
                        "Max wind" to (day.windSpeedMax?.let { "${it.roundToInt()} ${settings.windUnitLabel()}" } ?: "\u2014"),
                        "UV max" to (day.uvIndexMax?.oneDecimal() ?: "—"),
                        "Sunrise" to (day.sunrise?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "—"),
                        "Sunset" to (day.sunset?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "—")
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

private fun Double?.temp(settings: WeatherSettings): String =
    this?.let { "${it.roundToInt()}\u00B0${settings.temperatureSuffix()}" } ?: "\u2014"
private fun Double.oneDecimal(): String = "%.1f".format(this)
private fun Double?.wind(settings: WeatherSettings, direction: Int?): String =
    this?.let { "${it.oneDecimal()} ${settings.windUnitLabel()} ${direction?.compass() ?: ""}".trim() } ?: "\u2014"
private fun DailyForecast.minMax(settings: WeatherSettings): String =
    if (tempMin == null && tempMax == null) "\u2014" else "${tempMin.temp(settings)} / ${tempMax.temp(settings)}"
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

private fun Double.seaTempLabel(): String = when {
    this < 6  -> "Dangerous"
    this < 11 -> "Extremely cold"
    this < 16 -> "Cold — wetsuit essential"
    this < 23 -> "Wetsuit weather"
    this < 26 -> "Comfortable"
    else      -> "Warm"
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
