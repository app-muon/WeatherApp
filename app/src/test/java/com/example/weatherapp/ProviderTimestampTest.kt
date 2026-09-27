package com.example.weatherapp

import com.example.weatherapp.data.api.AemetApiClient
import com.example.weatherapp.data.api.MetNorwayApiClient
import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.data.provider.AemetProvider
import com.example.weatherapp.data.provider.MetNorwayProvider
import com.example.weatherapp.domain.model.WeatherUnits
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ProviderTimestampTest {
    private val now = Instant.parse("2026-09-27T12:20:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val units = WeatherUnits("celsius", "kmh", "mm")
    private val location = LocationEntity(1, "Madrid", "Spain", "ES", null, 40.4, -3.7, "Europe/Madrid", 0, 0)

    @Test fun metNorwayUsesHourNearestClockAndPreservesConditionsTimestamp() = runTest {
        val api = object : MetNorwayApiClient {
            override suspend fun forecast(latitude: Double, longitude: Double, userAgent: String): JsonObject =
                JsonParser.parseString("""
                    {"properties":{"timeseries":[
                    {"time":"2026-09-27T00:00:00Z","data":{"instant":{"details":{"air_temperature":5}}}},
                    {"time":"2026-09-27T12:00:00Z","data":{"instant":{"details":{"air_temperature":20}}}}
                    ]}}
                """).asJsonObject
        }
        val forecast = MetNorwayProvider(api, Gson(), clock).fetchForecast(location, units).forecast
        assertEquals(20.0, forecast.current!!.temperature!!, 0.0)
        assertEquals(Instant.parse("2026-09-27T12:00:00Z"), forecast.current!!.time.toInstant())
        assertEquals(now, forecast.fetchedAt)
    }

    @Test fun aemetDoesNotUseFirstHourOfTheDayAsCurrent() = runTest {
        val api = object : AemetApiClient {
            override suspend fun request(url: String, apiKey: String): JsonObject = JsonObject().apply {
                addProperty("datos", when {
                    url.contains("maestro") -> "municipalities"
                    url.contains("horaria") -> "hourly"
                    else -> "daily"
                })
            }
            override suspend fun data(url: String): JsonElement = JsonParser.parseString(when (url) {
                "municipalities" -> """[{"id":"id28079","nombre":"Madrid","latitud_dec":40.4,"longitud_dec":-3.7}]"""
                "hourly" -> """[{"prediccion":{"dia":[{"fecha":"2026-09-27","temperatura":[{"periodo":"00","value":"5"},{"periodo":"14","value":"20"}]}]}}]"""
                else -> """[{"prediccion":{"dia":[]}}]"""
            })
        }
        val forecast = AemetProvider(api, "test", Gson(), clock).fetchForecast(location, units).forecast
        assertEquals(20.0, forecast.current!!.temperature!!, 0.0)
        assertEquals(14, forecast.current!!.time.hour)
        assertEquals(now, forecast.fetchedAt)
    }
}
