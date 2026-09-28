package com.example.weatherapp

import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.data.repository.LocationForecast
import com.example.weatherapp.domain.model.*
import com.example.weatherapp.ui.widget.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WidgetLayoutTest {
    private val now = Instant.parse("2026-09-27T23:30:00Z")
    private fun metrics(scale: Float = 1f) = object : WidgetLayoutMetrics {
        override val density = 1f
        override fun sizePx(style: WidgetTextStyle) = style.sp * scale
        override fun measure(text: String, style: WidgetTextStyle) = WidgetTextSize(
            text.sumOf { if (it in "MW") 8 else if (it in "il: /°") 3 else 6 }.toFloat() * style.sp / 12 * scale,
            kotlin.math.ceil(style.sp * 1.18f * scale))
    }
    private fun item(zone: String = "UTC", id: Long = 1): LocationForecast {
        val location = LocationEntity(id, "São José – a very long location", null, null, null, 0.0, 0.0, zone, 0, 0)
        val today = now.atZone(ZoneId.of(zone)).toLocalDate()
        val forecast = ProviderForecast("open_meteo", "Open-Meteo", id, now,
            CurrentWeather(now.minusSeconds(1800).atZone(ZoneOffset.UTC), -5.0, null, null, null, null, 2, null, null, null, null), emptyList(),
            (0..2).map { DailyForecast(today.plusDays(it.toLong()), 2, -10.0, 20.0, null, null, null, null, null, null, null, null) }, null)
        return LocationForecast(location, forecast, listOf(forecast), emptyList(), null, null, "open_meteo")
    }
    private fun rows() = listOf(item("Asia/Tokyo"), item("America/Los_Angeles", 2)).map { widgetLocationContent(it, false, now) }

    @Test fun acceptanceSizesKeepBothCompleteForecasts() {
        for (height in listOf(72f, 80f, 96f, 120f, 160f)) {
            val layout = chooseWidgetLayout(320f, height, metrics(), rows())
            assertEquals(2, layout.visibleLocations)
            assertTrue(layout.showForecast)
            assertEquals(4, layout.columnWidths.size)
            assertTrue(layout.requiredHeight <= height)
            assertEquals(13f, layout.typography.name.sp)
            assertEquals(height < 96, layout.inlineHeadings)
            assertTrue(layout.typography.iconSize >= 16f)
        }
        assertEquals(9f, chooseWidgetLayout(320f, 96f, metrics(), rows()).typography.date.sp)
        assertEquals(18f, chooseWidgetLayout(320f, 160f, metrics(), rows()).typography.iconSize)
    }

    @Test fun shortWidgetsRetainFirstCompleteForecastAndLargeFontsReduceContent() {
        val short = chooseWidgetLayout(320f, 60f, metrics(), rows())
        assertEquals(1, short.visibleLocations)
        assertTrue(short.showForecast)
        for (scale in listOf(1.3f, 2f)) {
            val result = chooseWidgetLayout(320f, 96f, metrics(scale), rows())
            assertEquals(1, result.visibleLocations)
            assertTrue(result.requiredHeight <= 96f)
            assertEquals(12f, result.typography.value.sp)
        }
        assertFalse(chooseWidgetLayout(320f, 96f, metrics(2f), rows()).showForecast)
        val smallest = chooseWidgetLayout(320f, 48f, metrics(2f), rows())
        assertTrue(smallest.currentInHeader)
        assertTrue(smallest.requiredHeight <= 48)
    }

    @Test fun shortWeekdaysComeBeforeDroppingForecasts() {
        val narrow = rows().map { row -> row.copy(cells = row.cells.mapIndexed { index, cell ->
            if (index == 0 || index == 1) cell else cell.copy(label = "Wednesday 30 September", shortLabel = "Wed", temperature = "—")
        }) }
        val result = chooseWidgetLayout(250f, 96f, metrics(), narrow)
        assertTrue(result.abbreviatedDays)
        assertTrue(result.showForecast)
        assertEquals(2, result.visibleLocations)
        assertFalse(chooseWidgetLayout(120f, 96f, metrics(), rows()).showForecast)
    }

    @Test fun actualTemperaturesDetermineWidthAndNeverGetShortened() {
        val extreme = rows().map { row -> row.copy(cells = row.cells.map { it.copy(temperature = "100°/-100°") }) }
        assertTrue(chooseWidgetLayout(320f, 96f, metrics(), rows()).showForecast)
        assertTrue(chooseWidgetLayout(320f, 96f, metrics(), extreme).showForecast)
        assertFalse(chooseWidgetLayout(240f, 72f, metrics(), extreme).showForecast)
        assertTrue(chooseWidgetLayout(600f, 120f, metrics(), extreme).showForecast)
        assertEquals("100°/-100°", extreme.first().cells.last().temperature)
    }

    @Test fun inlineLayoutBorrowsColumnSpaceForOlderConditionTimestamps() {
        val old = rows().map { row -> row.copy(cells = row.cells.mapIndexed { index, cell ->
            if (index == 0) cell.copy(label = "27/12 23:00", shortLabel = "27/12 23:00") else cell
        }) }
        val result = chooseWidgetLayout(400f, 72f, metrics(), old)
        assertEquals(2, result.visibleLocations)
        assertTrue(result.showForecast)
        assertTrue(result.inlineHeadings)
        assertTrue(result.abbreviatedDays)
        assertTrue(result.columnWidths.first() > result.columnWidths.sum() * .2f)
        assertTrue(result.requiredHeight <= 72)
    }

    @Test fun singleLocationActionsDoNotDisplaceWeather() {
        val rows = rows().take(1)
        assertEquals(AddLocationAction.Label, chooseWidgetLayout(320f, 96f, metrics(), rows).addLocation)
        val short = chooseWidgetLayout(320f, 48f, metrics(), rows)
        assertEquals(AddLocationAction.Plus, short.addLocation)
        assertEquals(1, short.visibleLocations)
        assertEquals(0, chooseWidgetLayout(120f, 48f, metrics(), emptyList()).visibleLocations)
    }

    @Test fun statusUsesLocalDownloadDayAndKeepsConditionTimestampSeparate() {
        val tokyo = item("Asia/Tokyo")
        val la = item("America/Los_Angeles")
        assertEquals("Updated 08:30", compactWidgetStatus(tokyo, false, now))
        assertEquals("Updated 16:30", compactWidgetStatus(la, false, now))
        val content = widgetLocationContent(tokyo, false, now)
        assertEquals("08:00", content.cells.first().label)
        assertEquals("", content.cells[1].label)
        assertEquals("", content.cells[1].shortLabel)
        assertEquals("Tue 29", content.cells[2].label)
        assertEquals("Tue", content.cells[2].shortLabel)
        assertEquals("Mon", widgetLocationContent(la, false, now).cells[2].shortLabel)
        assertTrue(content.cells[1].description.startsWith("2026-09-28."))
        val previousDay = tokyo.copy(forecast = tokyo.forecast!!.copy(fetchedAt = now.minusSeconds(9 * 3600)))
        assertEquals("Old · 27/9 23:30", compactWidgetStatus(previousDay, false, now))
        assertTrue(widgetStatus(tokyo, false, now).contains("Downloaded 2026-09-28T08:30"))
    }

    @Test fun fallbackFailureActiveAndMissingValuesStayDistinct() {
        val fallback = item().copy(forecast = item().forecast!!.copy(providerId = "met_norway", providerName = "MET Norway"))
        assertEquals("Yr fallback · 23:30", compactWidgetStatus(fallback, false, now))
        assertEquals("Failed · 23:30", compactWidgetStatus(fallback.copy(lastRefreshFailed = true), false, now))
        assertEquals("Failed · 23:30", compactWidgetStatus(fallback, false, now, "Storage error"))
        assertTrue(widgetStatus(fallback, false, now, "Storage error").contains("Storage error"))
        assertEquals("Updating…", compactWidgetStatus(fallback, true, now, "Storage error"))
        val missing = item().copy(forecast = null)
        val content = widgetLocationContent(missing, false, now)
        assertEquals("Unavailable", content.status)
        assertTrue(content.cells.all { it.temperature == "—" })
        assertEquals("Current conditions unavailable", content.cells.first().description)
        assertEquals("Failed", compactWidgetStatus(missing.copy(lastRefreshFailed = true), false, now))
        val invalid = item().copy(forecast = item().forecast!!.copy(current = item().forecast!!.current!!.copy(temperature = Double.NaN)))
        assertEquals("—", widgetLocationContent(invalid, false, now).cells.first().temperature)
    }
}
