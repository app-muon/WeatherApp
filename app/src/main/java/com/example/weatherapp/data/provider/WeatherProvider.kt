package com.example.weatherapp.data.provider

import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.domain.model.ProviderForecast
import com.example.weatherapp.domain.model.WeatherUnits
import com.google.gson.JsonObject

data class ProviderFetchResult(
    val forecast: ProviderForecast,
    val rawJson: String
)

interface WeatherProvider {
    val id: String
    val displayName: String
    val shortName: String

    suspend fun isAvailableFor(location: LocationEntity): Boolean

    suspend fun fetchForecast(
        location: LocationEntity,
        units: WeatherUnits
    ): ProviderFetchResult
}

object WeatherProviderIds {
    const val AUTO = "auto"
    const val OPEN_METEO = "open_meteo"
    const val MET_NORWAY = "met_norway"
    const val MET_OFFICE = "met_office"
    const val AEMET = "aemet"
}

fun LocationEntity.countryCodeOrName(): String =
    countryCode?.uppercase()
        ?: when (country?.trim()?.lowercase()) {
            "united kingdom", "uk", "great britain" -> "GB"
            "spain", "espa\u00f1a", "espana" -> "ES"
            else -> ""
        }

fun unavailableProviderPayload(providerId: String, providerName: String): JsonObject =
    JsonObject().apply {
        addProperty("providerId", providerId)
        addProperty("providerName", providerName)
        addProperty("unavailable", true)
    }
