package com.example.weatherapp.domain.mapper

import com.example.weatherapp.domain.model.MarineConditions
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class MarineParser {
    fun parse(rawJson: String, referenceTime: ZonedDateTime): MarineConditions? {
        val root = JsonParser.parseString(rawJson).asJsonObject
        return parse(root, referenceTime)
    }

    fun parse(root: JsonObject, referenceTime: ZonedDateTime): MarineConditions? {
        val zone = zone(root, referenceTime.zone)
        val hourly = root.getAsJsonObject("hourly")
        val times = hourly.getAsJsonArray("time")
        if (times.size() == 0) return null

        val bestIndex = (0 until times.size()).minByOrNull { index ->
            kotlin.math.abs(
                ZonedDateTime.of(LocalDateTime.parse(times[index].asString), zone)
                    .toInstant()
                    .toEpochMilli() - referenceTime.toInstant().toEpochMilli()
            )
        } ?: return null

        return MarineConditions(
            time = ZonedDateTime.of(LocalDateTime.parse(times[bestIndex].asString), zone),
            seaSurfaceTemperature = hourly.doubleAtOrNull("sea_surface_temperature", bestIndex),
            waveHeight = hourly.doubleAtOrNull("wave_height", bestIndex),
            waveDirection = hourly.intAtOrNull("wave_direction", bestIndex),
            wavePeriod = hourly.doubleAtOrNull("wave_period", bestIndex)
        )
    }

    private fun zone(root: JsonObject, fallback: ZoneId): ZoneId =
        runCatching { ZoneId.of(root.get("timezone").asString) }.getOrDefault(fallback)
}

private fun JsonObject.doubleAtOrNull(name: String, index: Int): Double? =
    if (has(name) && !getAsJsonArray(name)[index].isJsonNull) getAsJsonArray(name)[index].asDouble else null

private fun JsonObject.intAtOrNull(name: String, index: Int): Int? =
    if (has(name) && !getAsJsonArray(name)[index].isJsonNull) getAsJsonArray(name)[index].asInt else null
