package com.example.weatherapp.data.provider

import com.example.weatherapp.data.api.MetNorwayApiClient
import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.domain.model.CurrentWeather
import com.example.weatherapp.domain.model.DailyForecast
import com.example.weatherapp.domain.model.HourlyForecast
import com.example.weatherapp.domain.model.ProviderForecast
import com.example.weatherapp.domain.model.WeatherUnits
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

class MetNorwayProvider(
    private val api: MetNorwayApiClient,
    private val gson: Gson
) : WeatherProvider {
    override val id = WeatherProviderIds.MET_NORWAY
    override val displayName = "Yr / MET Norway"
    override val shortName = "Yr"

    override suspend fun isAvailableFor(location: LocationEntity): Boolean = true

    override suspend fun fetchForecast(location: LocationEntity, units: WeatherUnits): ProviderFetchResult {
        val raw = api.forecast(location.latitude, location.longitude)
        return ProviderFetchResult(
            forecast = parse(location, raw),
            rawJson = gson.toJson(raw)
        )
    }

    private fun parse(location: LocationEntity, root: JsonObject): ProviderForecast {
        val zone = location.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        val hourly = root.getAsJsonObject("properties")
            .getAsJsonArray("timeseries")
            .map { item ->
                val obj = item.asJsonObject
                val time = Instant.parse(obj.get("time").asString).atZone(zone)
                val data = obj.getAsJsonObject("data")
                val instant = data.getAsJsonObject("instant").getAsJsonObject("details")
                val nextHour = data.objOrNull("next_1_hours")
                HourlyForecast(
                    time = time,
                    temperature = instant.doubleOrNull("air_temperature"),
                    feelsLike = instant.doubleOrNull("air_temperature"),
                    humidity = instant.doubleOrNull("relative_humidity")?.roundToInt(),
                    precipitationProbability = nextHour?.getAsJsonObject("details")?.doubleOrNull("probability_of_precipitation")?.roundToInt(),
                    precipitation = nextHour?.getAsJsonObject("details")?.doubleOrNull("precipitation_amount"),
                    rain = nextHour?.getAsJsonObject("details")?.doubleOrNull("precipitation_amount"),
                    weatherCode = nextHour?.getAsJsonObject("summary")?.stringOrNull("symbol_code")?.toWeatherCode(),
                    cloudCover = instant.doubleOrNull("cloud_area_fraction")?.roundToInt(),
                    pressure = instant.doubleOrNull("air_pressure_at_sea_level"),
                    visibility = null,
                    windSpeed = instant.doubleOrNull("wind_speed")?.let { ms -> ms * 3.6 },
                    windDirection = instant.doubleOrNull("wind_from_direction")?.roundToInt(),
                    uvIndex = instant.doubleOrNull("ultraviolet_index_clear_sky")
                )
            }
        val daily = hourly.groupBy { it.time.toLocalDate() }
            .toSortedMap()
            .entries
            .take(7)
            .map { (date, values) -> values.toDaily(date) }
        return ProviderForecast(
            providerId = id,
            providerName = displayName,
            locationId = location.id,
            fetchedAt = Instant.now(),
            current = hourly.firstOrNull()?.toCurrent(),
            hourly = hourly,
            daily = daily,
            attribution = "MET Norway"
        )
    }
}

private fun List<HourlyForecast>.toDaily(date: LocalDate) = DailyForecast(
    date = date,
    weatherCode = firstNotNullOfOrNull { it.weatherCode },
    tempMin = mapNotNull { it.temperature }.minOrNull(),
    tempMax = mapNotNull { it.temperature }.maxOrNull(),
    precipitationProbabilityMax = mapNotNull { it.precipitationProbability }.maxOrNull(),
    precipitationSum = mapNotNull { it.precipitation }.sumOrNull(),
    rainSum = mapNotNull { it.rain }.sumOrNull(),
    windSpeedMax = mapNotNull { it.windSpeed }.maxOrNull(),
    windDirectionDominant = firstNotNullOfOrNull { it.windDirection },
    sunrise = null,
    sunset = null,
    uvIndexMax = mapNotNull { it.uvIndex }.maxOrNull()
)

private fun HourlyForecast.toCurrent() = CurrentWeather(
    time = time,
    temperature = temperature,
    feelsLike = feelsLike,
    humidity = humidity,
    precipitation = precipitation,
    rain = rain,
    weatherCode = weatherCode,
    cloudCover = cloudCover,
    pressure = pressure,
    windSpeed = windSpeed,
    windDirection = windDirection
)

private fun List<Double>.sumOrNull(): Double? = if (isEmpty()) null else sum()

private fun String.toWeatherCode(): Int = when {
    contains("thunder") -> 95
    contains("snow") || contains("sleet") -> 71
    contains("rain") -> 61
    contains("fog") -> 45
    contains("partlycloudy") -> 2
    contains("cloudy") -> 3
    else -> 0
}

private fun JsonObject.objOrNull(name: String): JsonObject? =
    if (has(name) && !get(name).isJsonNull) getAsJsonObject(name) else null
private fun JsonObject.doubleOrNull(name: String): Double? =
    if (has(name) && !get(name).isJsonNull) get(name).asDouble else null
private fun JsonObject.stringOrNull(name: String): String? =
    if (has(name) && !get(name).isJsonNull) get(name).asString else null
