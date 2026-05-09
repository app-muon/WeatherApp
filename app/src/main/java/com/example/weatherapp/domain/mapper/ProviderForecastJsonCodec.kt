package com.example.weatherapp.domain.mapper

import com.example.weatherapp.domain.model.CurrentWeather
import com.example.weatherapp.domain.model.DailyForecast
import com.example.weatherapp.domain.model.HourlyForecast
import com.example.weatherapp.domain.model.ProviderForecast
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

class ProviderForecastJsonCodec {
    fun encode(forecast: ProviderForecast): String {
        val root = JsonObject()
        root.addProperty("providerId", forecast.providerId)
        root.addProperty("providerName", forecast.providerName)
        root.addProperty("locationId", forecast.locationId)
        root.addProperty("fetchedAt", forecast.fetchedAt.toString())
        forecast.attribution?.let { root.addProperty("attribution", it) }
        forecast.current?.let { root.add("current", it.toJson()) }
        root.add("hourly", JsonArray().apply { forecast.hourly.forEach { add(it.toJson()) } })
        root.add("daily", JsonArray().apply { forecast.daily.forEach { add(it.toJson()) } })
        return root.toString()
    }

    fun decode(rawJson: String): ProviderForecast {
        val root = JsonParser.parseString(rawJson).asJsonObject
        return ProviderForecast(
            providerId = root.string("providerId"),
            providerName = root.string("providerName"),
            locationId = root.long("locationId"),
            fetchedAt = Instant.parse(root.string("fetchedAt")),
            current = root.objOrNull("current")?.toCurrent(),
            hourly = root.arrayOrEmpty("hourly").map { it.asJsonObject.toHourly() },
            daily = root.arrayOrEmpty("daily").map { it.asJsonObject.toDaily() },
            attribution = root.stringOrNull("attribution")
        )
    }
}

private fun CurrentWeather.toJson() = JsonObject().also {
    it.addProperty("time", time.toString())
    it.addNullable("temperature", temperature)
    it.addNullable("feelsLike", feelsLike)
    it.addNullable("humidity", humidity)
    it.addNullable("precipitation", precipitation)
    it.addNullable("rain", rain)
    it.addNullable("weatherCode", weatherCode)
    it.addNullable("cloudCover", cloudCover)
    it.addNullable("pressure", pressure)
    it.addNullable("windSpeed", windSpeed)
    it.addNullable("windDirection", windDirection)
}

private fun HourlyForecast.toJson() = JsonObject().also {
    it.addProperty("time", time.toString())
    it.addNullable("temperature", temperature)
    it.addNullable("feelsLike", feelsLike)
    it.addNullable("humidity", humidity)
    it.addNullable("precipitationProbability", precipitationProbability)
    it.addNullable("precipitation", precipitation)
    it.addNullable("rain", rain)
    it.addNullable("weatherCode", weatherCode)
    it.addNullable("cloudCover", cloudCover)
    it.addNullable("pressure", pressure)
    it.addNullable("visibility", visibility)
    it.addNullable("windSpeed", windSpeed)
    it.addNullable("windDirection", windDirection)
    it.addNullable("uvIndex", uvIndex)
}

private fun DailyForecast.toJson() = JsonObject().also {
    it.addProperty("date", date.toString())
    it.addNullable("weatherCode", weatherCode)
    it.addNullable("tempMin", tempMin)
    it.addNullable("tempMax", tempMax)
    it.addNullable("precipitationProbabilityMax", precipitationProbabilityMax)
    it.addNullable("precipitationSum", precipitationSum)
    it.addNullable("rainSum", rainSum)
    it.addNullable("windSpeedMax", windSpeedMax)
    it.addNullable("windDirectionDominant", windDirectionDominant)
    sunrise?.let { value -> it.addProperty("sunrise", value.toString()) }
    sunset?.let { value -> it.addProperty("sunset", value.toString()) }
    it.addNullable("uvIndexMax", uvIndexMax)
}

private fun JsonObject.toCurrent() = CurrentWeather(
    time = ZonedDateTime.parse(string("time")),
    temperature = doubleOrNull("temperature"),
    feelsLike = doubleOrNull("feelsLike"),
    humidity = intOrNull("humidity"),
    precipitation = doubleOrNull("precipitation"),
    rain = doubleOrNull("rain"),
    weatherCode = intOrNull("weatherCode"),
    cloudCover = intOrNull("cloudCover"),
    pressure = doubleOrNull("pressure"),
    windSpeed = doubleOrNull("windSpeed"),
    windDirection = intOrNull("windDirection")
)

private fun JsonObject.toHourly() = HourlyForecast(
    time = ZonedDateTime.parse(string("time")),
    temperature = doubleOrNull("temperature"),
    feelsLike = doubleOrNull("feelsLike"),
    humidity = intOrNull("humidity"),
    precipitationProbability = intOrNull("precipitationProbability"),
    precipitation = doubleOrNull("precipitation"),
    rain = doubleOrNull("rain"),
    weatherCode = intOrNull("weatherCode"),
    cloudCover = intOrNull("cloudCover"),
    pressure = doubleOrNull("pressure"),
    visibility = doubleOrNull("visibility"),
    windSpeed = doubleOrNull("windSpeed"),
    windDirection = intOrNull("windDirection"),
    uvIndex = doubleOrNull("uvIndex")
)

private fun JsonObject.toDaily() = DailyForecast(
    date = LocalDate.parse(string("date")),
    weatherCode = intOrNull("weatherCode"),
    tempMin = doubleOrNull("tempMin"),
    tempMax = doubleOrNull("tempMax"),
    precipitationProbabilityMax = intOrNull("precipitationProbabilityMax"),
    precipitationSum = doubleOrNull("precipitationSum"),
    rainSum = doubleOrNull("rainSum"),
    windSpeedMax = doubleOrNull("windSpeedMax"),
    windDirectionDominant = intOrNull("windDirectionDominant"),
    sunrise = stringOrNull("sunrise")?.let { ZonedDateTime.parse(it) },
    sunset = stringOrNull("sunset")?.let { ZonedDateTime.parse(it) },
    uvIndexMax = doubleOrNull("uvIndexMax")
)

private fun JsonObject.addNullable(name: String, value: Double?) {
    if (value == null) add(name, null) else addProperty(name, value)
}

private fun JsonObject.addNullable(name: String, value: Int?) {
    if (value == null) add(name, null) else addProperty(name, value)
}

private fun JsonObject.objOrNull(name: String): JsonObject? =
    if (has(name) && !get(name).isJsonNull) getAsJsonObject(name) else null

private fun JsonObject.arrayOrEmpty(name: String): JsonArray =
    if (has(name) && !get(name).isJsonNull) getAsJsonArray(name) else JsonArray()

private fun JsonObject.string(name: String): String = get(name).asString
private fun JsonObject.long(name: String): Long = get(name).asLong
private fun JsonObject.stringOrNull(name: String): String? =
    if (has(name) && !get(name).isJsonNull) get(name).asString else null
private fun JsonObject.doubleOrNull(name: String): Double? =
    if (has(name) && !get(name).isJsonNull) get(name).asDouble else null
private fun JsonObject.intOrNull(name: String): Int? =
    if (has(name) && !get(name).isJsonNull) get(name).asInt else null
