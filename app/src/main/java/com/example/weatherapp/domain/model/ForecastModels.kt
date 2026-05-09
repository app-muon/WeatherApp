package com.example.weatherapp.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

data class CurrentWeather(
    val time: ZonedDateTime,
    val temperature: Double?,
    val feelsLike: Double?,
    val humidity: Int?,
    val precipitation: Double?,
    val rain: Double?,
    val weatherCode: Int?,
    val cloudCover: Int?,
    val pressure: Double?,
    val windSpeed: Double?,
    val windDirection: Int?
)

data class HourlyForecast(
    val time: ZonedDateTime,
    val temperature: Double?,
    val feelsLike: Double?,
    val humidity: Int?,
    val precipitationProbability: Int?,
    val precipitation: Double?,
    val rain: Double?,
    val weatherCode: Int?,
    val cloudCover: Int?,
    val pressure: Double?,
    val visibility: Double?,
    val windSpeed: Double?,
    val windDirection: Int?,
    val uvIndex: Double?
)

data class DailyForecast(
    val date: LocalDate,
    val weatherCode: Int?,
    val tempMin: Double?,
    val tempMax: Double?,
    val precipitationProbabilityMax: Int?,
    val precipitationSum: Double?,
    val rainSum: Double?,
    val windSpeedMax: Double?,
    val windDirectionDominant: Int?,
    val sunrise: ZonedDateTime?,
    val sunset: ZonedDateTime?,
    val uvIndexMax: Double?
)

data class ProviderForecast(
    val providerId: String,
    val providerName: String,
    val locationId: Long,
    val fetchedAt: Instant,
    val current: CurrentWeather?,
    val hourly: List<HourlyForecast>,
    val daily: List<DailyForecast>,
    val attribution: String?
)

typealias Forecast = ProviderForecast

data class ProviderStatus(
    val providerId: String,
    val locationId: Long,
    val lastFetchedAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastError: String?
)

data class WidgetSourcePreference(
    val locationId: Long,
    val selectedProviderId: String
)

data class ForecastSourcePreference(
    val locationId: Long,
    val selectedProviderId: String
)

data class WeatherUnits(
    val temperatureUnit: String,
    val windSpeedUnit: String,
    val precipitationUnit: String
)

data class MarineConditions(
    val time: ZonedDateTime,
    val seaSurfaceTemperature: Double?,
    val waveHeight: Double?,
    val waveDirection: Int?,
    val wavePeriod: Double?
)

data class WeatherCondition(
    val code: Int,
    val label: String,
    val icon: WeatherIcon
)

enum class WeatherIcon {
    Clear,
    PartlyCloudy,
    Cloudy,
    Fog,
    Rain,
    Snow,
    Thunderstorm
}
