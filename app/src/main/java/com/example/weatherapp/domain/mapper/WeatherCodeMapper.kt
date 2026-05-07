package com.example.weatherapp.domain.mapper

import com.example.weatherapp.R
import com.example.weatherapp.domain.model.WeatherCondition
import com.example.weatherapp.domain.model.WeatherIcon

object WeatherCodeMapper {
    fun condition(code: Int): WeatherCondition = when (code) {
        0 -> WeatherCondition(code, "Clear", WeatherIcon.Clear)
        1 -> WeatherCondition(code, "Mainly clear", WeatherIcon.Clear)
        2 -> WeatherCondition(code, "Partly cloudy", WeatherIcon.PartlyCloudy)
        3 -> WeatherCondition(code, "Overcast", WeatherIcon.Cloudy)
        45 -> WeatherCondition(code, "Fog", WeatherIcon.Fog)
        48 -> WeatherCondition(code, "Rime fog", WeatherIcon.Fog)
        51 -> WeatherCondition(code, "Light drizzle", WeatherIcon.Rain)
        53 -> WeatherCondition(code, "Drizzle", WeatherIcon.Rain)
        55 -> WeatherCondition(code, "Heavy drizzle", WeatherIcon.Rain)
        61 -> WeatherCondition(code, "Light rain", WeatherIcon.Rain)
        63 -> WeatherCondition(code, "Rain", WeatherIcon.Rain)
        65 -> WeatherCondition(code, "Heavy rain", WeatherIcon.Rain)
        71 -> WeatherCondition(code, "Light snow", WeatherIcon.Snow)
        73 -> WeatherCondition(code, "Snow", WeatherIcon.Snow)
        75 -> WeatherCondition(code, "Heavy snow", WeatherIcon.Snow)
        80 -> WeatherCondition(code, "Light showers", WeatherIcon.Rain)
        81 -> WeatherCondition(code, "Showers", WeatherIcon.Rain)
        82 -> WeatherCondition(code, "Heavy showers", WeatherIcon.Rain)
        95 -> WeatherCondition(code, "Thunderstorm", WeatherIcon.Thunderstorm)
        96 -> WeatherCondition(code, "Thunderstorm with hail", WeatherIcon.Thunderstorm)
        99 -> WeatherCondition(code, "Thunderstorm with heavy hail", WeatherIcon.Thunderstorm)
        else -> WeatherCondition(code, "Weather code $code", WeatherIcon.Cloudy)
    }

    fun drawableRes(code: Int): Int = when (condition(code).icon) {
        WeatherIcon.Clear -> R.drawable.ic_weather_clear
        WeatherIcon.PartlyCloudy -> R.drawable.ic_weather_partly_cloudy
        WeatherIcon.Cloudy -> R.drawable.ic_weather_cloudy
        WeatherIcon.Fog -> R.drawable.ic_weather_fog
        WeatherIcon.Rain -> R.drawable.ic_weather_rain
        WeatherIcon.Snow -> R.drawable.ic_weather_snow
        WeatherIcon.Thunderstorm -> R.drawable.ic_weather_thunder
    }
}

