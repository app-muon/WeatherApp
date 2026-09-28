package com.example.weatherapp

import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.data.repository.*
import com.example.weatherapp.domain.mapper.WeatherCodeMapper
import com.example.weatherapp.domain.model.*
import com.example.weatherapp.ui.forecast.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ForecastPresentationTest {
    private val now = Instant.parse("2026-09-27T23:30:00Z")

    @Test fun locationDatesDifferAcrossMidnight() {
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        assertEquals(LocalDate.parse("2026-09-28"), location("Asia/Tokyo").today(clock))
        assertEquals(LocalDate.parse("2026-09-27"), location("America/Los_Angeles").today(clock))
    }

    @Test fun oldProviderDatesAndGapsCannotShiftComparisonColumns() {
        val today = LocalDate.parse("2026-09-28")
        val old = forecast("old", now.minusSeconds(3600)).copy(daily = listOf(day(today.minusDays(1)), day(today.plusDays(1))))
        val columns = old.daysFrom(today, 3)
        assertEquals(listOf(today, today.plusDays(1), today.plusDays(2)), columns.map { it.first })
        assertNull(columns[0].second)
        assertEquals(today.plusDays(1), columns[1].second?.date)
        assertNull(columns[2].second)
    }

    @Test fun hourlyComparisonAlignsInstantsAndPreservesDstRepeatedHour() {
        val first = Instant.parse("2026-10-25T00:00:00Z")
        val second = first.plusSeconds(3600)
        val uk = forecast("uk", first).copy(hourly = listOf(hour(first.atZone(ZoneId.of("Europe/London"))), hour(second.atZone(ZoneId.of("Europe/London")))))
        val utc = forecast("utc", first).copy(hourly = listOf(hour(first.atZone(ZoneOffset.UTC)), hour(second.atZone(ZoneOffset.UTC))))
        assertEquals(listOf(first, second), listOf(uk, utc).upcomingInstants(first, 24))
        assertEquals(listOf(second), listOf(uk, utc).upcomingInstants(first.plusSeconds(1), 24))
        assertEquals(uk.hourly[0].time.hour, uk.hourly[1].time.hour)
    }

    @Test fun dateArithmeticUsesCalendarDaysAcrossDst() {
        val clock = Clock.fixed(Instant.parse("2026-03-29T00:30:00Z"), ZoneOffset.UTC)
        val today = location("Europe/London").today(clock)
        val dates = forecast("x", clock.instant()).daysFrom(today, 3).map { it.first }
        assertEquals(listOf(LocalDate.parse("2026-03-29"), LocalDate.parse("2026-03-30"), LocalDate.parse("2026-03-31")), dates)
    }

    @Test fun currentEstimateUsesTimestampAndRejectsExpiredHours() {
        val hours = listOf(hour(now.minusSeconds(7200).atZone(ZoneOffset.UTC)), hour(now.atZone(ZoneOffset.UTC)))
        assertEquals(now, hours.currentEstimate(now)?.time?.toInstant())
        assertNull(hours.take(1).currentEstimate(now))
    }

    @Test fun failedPreferredSourceUsesFreshFallbackThenBestRetainedCache() {
        val preferred = forecast("preferred", now.minusSeconds(180))
        val fallback = forecast("fallback", now.minusSeconds(60))
        assertEquals(fallback, selectDisplayForecast(listOf(preferred, fallback), listOf(preferred, fallback), setOf("preferred"), now))
        assertEquals(fallback, selectDisplayForecast(listOf(preferred, fallback), listOf(preferred, fallback), setOf("preferred", "fallback"), now))
        assertEquals(preferred, selectDisplayForecast(listOf(preferred, fallback), listOf(preferred, fallback), emptySet(), now))
    }

    @Test fun emptyResponsesAreNotSuccessfulForecasts() {
        assertFalse(forecast("empty", now).hasData())
        assertTrue(forecast("daily", now).copy(daily = listOf(day(LocalDate.parse("2026-09-28")))).hasData())
        assertFalse(forecast("malformed", now).copy(daily = listOf(day(LocalDate.parse("2026-09-28")).copy(tempMax = Double.NaN))).hasData())
        assertFalse(forecast("unknown", now).copy(daily = listOf(day(LocalDate.parse("2026-09-28")).copy(tempMin = null, tempMax = null, weatherCode = -1))).hasData())
        assertFalse(MarineConditions(now.atZone(ZoneOffset.UTC), null, null, null, null).hasData())
        assertEquals(WeatherIcon.Unknown, WeatherCodeMapper.condition(-1).icon)
        assertEquals(WeatherIcon.Unknown, WeatherCodeMapper.condition(999).icon)
    }

    @Test fun repeatedSameLocationWidgetTapResetsPageAndTab() {
        val original = NavigationState(AppPage.Locations, ContentScope.Marine, 1)
        val first = original.widgetEvent("tap-1", 1, false, listOf(1, 2))
        assertEquals(AppPage.Forecasts, first.page)
        assertEquals(ContentScope.Forecast, first.tab)
        val moved = first.copy(page = AppPage.Locations, tab = ContentScope.Compare)
        assertEquals(moved, moved.widgetEvent("tap-1", 1, false, listOf(1, 2)))
        assertEquals(ContentScope.Forecast, moved.widgetEvent("tap-2", 1, false, listOf(1, 2)).tab)
    }

    @Test fun missingAndEmptyWidgetTargetsResolveSafely() {
        val nav = NavigationState()
        assertEquals(2L, nav.widgetEvent("a", 99, false, listOf(2, 3)).locationId)
        assertEquals(AppPage.Locations, nav.widgetEvent("b", 99, false, emptyList()).page)
        assertEquals(AppPage.Locations, nav.widgetEvent("c", 2, true, listOf(2, 3)).page)
    }

    @Test fun statusesDistinguishLoadingRefreshingFailureAndUnavailable() {
        assertEquals("Loading…", updateStatusLabel(null, false, true, ZoneOffset.UTC))
        assertEquals("Updating…", updateStatusLabel(now, false, true, ZoneOffset.UTC))
        assertTrue(updateStatusLabel(now, true, false, ZoneOffset.UTC).startsWith("Update failed · showing data from"))
        assertEquals("Forecast unavailable", updateStatusLabel(null, false, false, ZoneOffset.UTC))
    }

    private fun location(zone: String) = LocationEntity(1, "Place", null, null, null, 0.0, 0.0, zone, 0, 0)
    private fun forecast(id: String, time: Instant) = ProviderForecast(id, id, 1, time, null, emptyList(), emptyList(), null)
    private fun day(date: LocalDate) = DailyForecast(date, null, 10.0, 20.0, null, null, null, null, null, null, null, null)
    private fun hour(time: ZonedDateTime) = HourlyForecast(time, 20.0, null, null, null, null, null, null, null, null, null, null, null, null)
}
