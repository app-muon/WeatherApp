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
        require(root.get("hourly")?.isJsonObject == true) { "Missing marine hourly data" }
        val hourly = root.getAsJsonObject("hourly")
        require(hourly.get("time")?.isJsonArray == true) { "Missing marine timestamps" }
        val times = hourly.getAsJsonArray("time")
        val variables = listOf("sea_surface_temperature", "wave_height", "wave_direction", "wave_period")
            .filter { hourly.has(it) }
        require(variables.isNotEmpty()) { "Missing marine variables" }
        variables.forEach { name ->
            val values = hourly.get(name)
            require(values.isJsonArray && values.asJsonArray.size() == times.size()) { "Invalid marine array: $name" }
            require(values.asJsonArray.all { it.isJsonNull ||
                (it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble.isFinite()) }) { "Invalid marine value: $name" }
        }
        val instants = times.map { ZonedDateTime.of(LocalDateTime.parse(it.asString), zone) }
        if (times.size() == 0) return null

        val bestIndex = (0 until times.size()).minByOrNull { index ->
            kotlin.math.abs(
                instants[index]
                    .toInstant()
                    .toEpochMilli() - referenceTime.toInstant().toEpochMilli()
            )
        } ?: return null

        return MarineConditions(
            time = instants[bestIndex],
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
