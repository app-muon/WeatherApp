package com.example.weatherapp.domain.mapper

import com.example.weatherapp.domain.model.CurrentWeather
import com.example.weatherapp.domain.model.DailyForecast
import com.example.weatherapp.domain.model.Forecast
import com.example.weatherapp.domain.model.HourlyForecast
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ForecastParser {
    fun parse(locationId: Long, fetchedAtEpochMillis: Long, rawJson: String): Forecast {
        val root = JsonParser.parseString(rawJson).asJsonObject
        return parse(locationId, Instant.ofEpochMilli(fetchedAtEpochMillis), root)
    }

    fun parse(locationId: Long, fetchedAt: Instant, root: JsonObject): Forecast {
        val zone = zone(root)
        val current = root.obj("current")
        val hourly = root.obj("hourly")
        val daily = root.obj("daily")

        return Forecast(
            providerId = "open_meteo",
            providerName = "Open-Meteo",
            locationId = locationId,
            fetchedAt = fetchedAt,
            current = CurrentWeather(
                time = dateTime(current.string("time"), zone),
                temperature = current.double("temperature_2m"),
                feelsLike = current.double("apparent_temperature"),
                humidity = current.int("relative_humidity_2m"),
                precipitation = current.double("precipitation"),
                rain = current.double("rain"),
                weatherCode = current.int("weather_code"),
                cloudCover = current.int("cloud_cover"),
                pressure = current.double("pressure_msl"),
                windSpeed = current.double("wind_speed_10m"),
                windDirection = current.int("wind_direction_10m")
            ),
            hourly = parseHourly(hourly, zone),
            daily = parseDaily(daily, zone),
            attribution = "Open-Meteo"
        )
    }

    private fun parseHourly(hourly: JsonObject, zone: ZoneId): List<HourlyForecast> {
        val times = hourly.array("time")
        return List(times.size()) { index ->
            HourlyForecast(
                time = dateTime(times[index].asString, zone),
                temperature = hourly.doubleAt("temperature_2m", index),
                feelsLike = hourly.doubleAt("apparent_temperature", index),
                humidity = hourly.intAt("relative_humidity_2m", index),
                precipitationProbability = hourly.intAtOrNull("precipitation_probability", index),
                precipitation = hourly.doubleAt("precipitation", index),
                rain = hourly.doubleAt("rain", index),
                weatherCode = hourly.intAt("weather_code", index),
                cloudCover = hourly.intAt("cloud_cover", index),
                pressure = hourly.doubleAt("pressure_msl", index),
                visibility = hourly.doubleAtOrNull("visibility", index),
                windSpeed = hourly.doubleAt("wind_speed_10m", index),
                windDirection = hourly.intAt("wind_direction_10m", index),
                uvIndex = hourly.doubleAtOrNull("uv_index", index)
            )
        }
    }

    private fun parseDaily(daily: JsonObject, zone: ZoneId): List<DailyForecast> {
        val dates = daily.array("time")
        return List(dates.size()) { index ->
            DailyForecast(
                date = LocalDate.parse(dates[index].asString),
                weatherCode = daily.intAt("weather_code", index),
                tempMin = daily.doubleAt("temperature_2m_min", index),
                tempMax = daily.doubleAt("temperature_2m_max", index),
                precipitationProbabilityMax = daily.intAtOrNull("precipitation_probability_max", index),
                precipitationSum = daily.doubleAt("precipitation_sum", index),
                rainSum = daily.doubleAt("rain_sum", index),
                windSpeedMax = daily.doubleAt("wind_speed_10m_max", index),
                windDirectionDominant = daily.intAtOrNull("wind_direction_10m_dominant", index),
                sunrise = daily.stringAtOrNull("sunrise", index)?.let { dateTime(it, zone) },
                sunset = daily.stringAtOrNull("sunset", index)?.let { dateTime(it, zone) },
                uvIndexMax = daily.doubleAtOrNull("uv_index_max", index)
            )
        }
    }

    private fun zone(root: JsonObject): ZoneId =
        runCatching { ZoneId.of(root.string("timezone")) }.getOrDefault(ZoneId.systemDefault())

    private fun dateTime(value: String, zone: ZoneId) =
        LocalDateTime.parse(value).atZone(zone)
}

private fun JsonObject.obj(name: String): JsonObject = getAsJsonObject(name)
private fun JsonObject.array(name: String): JsonArray = getAsJsonArray(name)
private fun JsonObject.string(name: String): String = get(name).asString
private fun JsonObject.double(name: String): Double = get(name).asDouble
private fun JsonObject.int(name: String): Int = get(name).asInt
private fun JsonObject.doubleAt(name: String, index: Int): Double = array(name)[index].asDouble
private fun JsonObject.intAt(name: String, index: Int): Int = array(name)[index].asInt
private fun JsonObject.doubleAtOrNull(name: String, index: Int): Double? =
    if (has(name) && !array(name)[index].isJsonNull) array(name)[index].asDouble else null
private fun JsonObject.intAtOrNull(name: String, index: Int): Int? =
    if (has(name) && !array(name)[index].isJsonNull) array(name)[index].asInt else null
private fun JsonObject.stringAtOrNull(name: String, index: Int): String? =
    if (has(name) && !array(name)[index].isJsonNull) array(name)[index].asString else null
