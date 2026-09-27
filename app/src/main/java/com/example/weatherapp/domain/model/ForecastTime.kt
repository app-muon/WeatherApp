package com.example.weatherapp.domain.model

import com.example.weatherapp.data.db.LocationEntity
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun LocationEntity.zone(): ZoneId = timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")
fun LocationEntity.today(clock: Clock): LocalDate = clock.instant().atZone(zone()).toLocalDate()

/** Missing dates keep their columns; an old download cannot turn yesterday into today. */
fun ProviderForecast.daysFrom(today: LocalDate, count: Int): List<Pair<LocalDate, DailyForecast?>> =
    (0 until count).map { offset ->
        val date = today.plusDays(offset.toLong())
        date to daily.find { it.date == date }
    }

fun List<HourlyForecast>.currentEstimate(now: Instant): HourlyForecast? =
    filter { Duration.between(it.time.toInstant(), now).abs() <= Duration.ofHours(1) }
        .minByOrNull { Duration.between(it.time.toInstant(), now).abs() }

fun List<ProviderForecast>.upcomingInstants(now: Instant, count: Int): List<Instant> =
    flatMap { forecast -> forecast.hourly.map { it.time.toInstant() } }
        .filter { !it.isBefore(now) }.distinct().sorted().take(count)
