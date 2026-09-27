package com.example.weatherapp

import com.example.weatherapp.domain.mapper.MarineParser
import com.example.weatherapp.data.repository.hasData
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class MarineParserTest {
    private val parser = MarineParser()
    private val now = ZonedDateTime.parse("2026-09-27T12:00:00Z")

    @Test fun validNullValuesAndEmptyArraysAreUnavailable() {
        val nullValues = parser.parse("""{"timezone":"UTC","hourly":{"time":["2026-09-27T12:00"],"wave_height":[null],"sea_surface_temperature":[null]}}""", now)
        assertNotNull(nullValues)
        assertFalse(nullValues!!.hasData())
        assertNull(parser.parse("""{"hourly":{"time":[],"wave_height":[]}}""", now))
    }

    @Test fun missingOrMalformedMarineArraysRemainFailures() {
        val malformed = listOf(
            """{}""",
            """{"hourly":{"time":[]}}""",
            """{"hourly":{"time":["2026-09-27T12:00"],"wave_height":[]}}""",
            """{"hourly":{"time":["2026-09-27T12:00"],"wave_height":["bad"]}}""",
            """{"hourly":{"time":["2026-09-27T12:00"],"wave_height":[1e999]}}""",
            """{"hourly":{"time":["bad"],"wave_height":[null]}}"""
        )
        for (json in malformed) {
            assertTrue("Malformed marine data was accepted: $json", runCatching { parser.parse(json, now) }.isFailure)
        }
    }

    @Test fun currentHourCanHavePartialMarineData() {
        val conditions = parser.parse("""{"timezone":"UTC","hourly":{"time":["2026-09-27T00:00","2026-09-27T12:00"],"wave_height":[9.0,1.5],"sea_surface_temperature":[null,null]}}""", now)!!
        assertEquals(now.toInstant(), conditions.time.toInstant())
        assertEquals(1.5, conditions.waveHeight!!, 0.0)
        assertNull(conditions.seaSurfaceTemperature)
        assertTrue(conditions.hasData())
    }
}
