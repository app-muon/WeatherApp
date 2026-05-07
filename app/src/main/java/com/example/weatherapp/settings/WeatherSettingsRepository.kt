package com.example.weatherapp.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.weatherSettingsDataStore by preferencesDataStore("weather_settings")

data class WeatherSettings(
    val temperatureUnit: String = "celsius",
    val windSpeedUnit: String = "kmh",
    val precipitationUnit: String = "mm"
)

class WeatherSettingsRepository(private val context: Context) {
    private object Keys {
        val temperatureUnit = stringPreferencesKey("temperature_unit")
        val windSpeedUnit = stringPreferencesKey("wind_speed_unit")
        val precipitationUnit = stringPreferencesKey("precipitation_unit")
    }

    val settings: Flow<WeatherSettings> = context.weatherSettingsDataStore.data.map { preferences ->
        WeatherSettings(
            temperatureUnit = preferences[Keys.temperatureUnit] ?: "celsius",
            windSpeedUnit = preferences[Keys.windSpeedUnit] ?: "kmh",
            precipitationUnit = preferences[Keys.precipitationUnit] ?: "mm"
        )
    }

    suspend fun update(settings: WeatherSettings) {
        context.weatherSettingsDataStore.edit { preferences ->
            preferences[Keys.temperatureUnit] = settings.temperatureUnit
            preferences[Keys.windSpeedUnit] = settings.windSpeedUnit
            preferences[Keys.precipitationUnit] = settings.precipitationUnit
        }
    }
}

