package com.example.weatherapp.data.provider

import com.example.weatherapp.data.api.WeatherApiClient
import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.domain.mapper.ForecastParser
import com.example.weatherapp.domain.model.WeatherUnits
import com.google.gson.Gson
import java.time.Instant

class OpenMeteoProvider(
    private val weatherApi: WeatherApiClient,
    private val forecastParser: ForecastParser,
    private val gson: Gson
) : WeatherProvider {
    override val id = WeatherProviderIds.OPEN_METEO
    override val displayName = "Open-Meteo"
    override val shortName = "OM"

    override suspend fun isAvailableFor(location: LocationEntity): Boolean = true

    override suspend fun fetchForecast(location: LocationEntity, units: WeatherUnits): ProviderFetchResult {
        val raw = weatherApi.forecast(
            latitude = location.latitude,
            longitude = location.longitude,
            temperatureUnit = units.temperatureUnit,
            windSpeedUnit = units.windSpeedUnit,
            precipitationUnit = units.precipitationUnit
        )
        val forecast = forecastParser.parse(location.id, Instant.now(), raw)
        return ProviderFetchResult(
            forecast = forecast.copy(providerId = id, providerName = displayName, attribution = "Open-Meteo"),
            rawJson = gson.toJson(raw)
        )
    }
}
