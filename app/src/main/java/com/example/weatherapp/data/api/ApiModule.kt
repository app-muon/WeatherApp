package com.example.weatherapp.data.api

import com.example.weatherapp.BuildConfig
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import retrofit2.http.Url

object ApiModule {
    private val client = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            }
        )
        .build()

    private fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val geocodingApi: GeocodingApiClient = retrofit("https://geocoding-api.open-meteo.com/")
        .create(GeocodingApiClient::class.java)

    val weatherApi: WeatherApiClient = retrofit("https://api.open-meteo.com/")
        .create(WeatherApiClient::class.java)

    val marineApi: MarineApiClient = retrofit("https://marine-api.open-meteo.com/")
        .create(MarineApiClient::class.java)

    val metNorwayApi: MetNorwayApiClient = retrofit("https://api.met.no/")
        .create(MetNorwayApiClient::class.java)

    val metOfficeApi: MetOfficeApiClient = retrofit("https://data.hub.api.metoffice.gov.uk/")
        .create(MetOfficeApiClient::class.java)

    val aemetApi: AemetApiClient = retrofit("https://opendata.aemet.es/")
        .create(AemetApiClient::class.java)
}

interface GeocodingApiClient {
    @GET("v1/search")
    suspend fun searchLocations(
        @Query("name") name: String,
        @Query("count") count: Int = 10,
        @Query("language") language: String = "en",
        @Query("format") format: String = "json"
    ): GeocodingResponse
}

interface WeatherApiClient {
    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("timezone") timezone: String = "auto",
        @Query("forecast_days") forecastDays: Int = 7,
        @Query("current") current: String = WeatherApiFields.CURRENT,
        @Query("hourly") hourly: String = WeatherApiFields.HOURLY,
        @Query("daily") daily: String = WeatherApiFields.DAILY,
        @Query("temperature_unit") temperatureUnit: String,
        @Query("wind_speed_unit") windSpeedUnit: String,
        @Query("precipitation_unit") precipitationUnit: String
    ): JsonObject
}

interface MarineApiClient {
    @GET("v1/marine")
    suspend fun marine(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("hourly") hourly: String = MarineApiFields.HOURLY,
        @Query("timezone") timezone: String = "auto"
    ): JsonObject
}

interface MetNorwayApiClient {
    @GET("weatherapi/locationforecast/2.0/compact")
    suspend fun forecast(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Header("User-Agent") userAgent: String = "WeatherApp/1.0 app-muon"
    ): JsonObject
}

interface MetOfficeApiClient {
    @GET
    suspend fun pointForecast(
        @Url url: String,
        @Header("apikey") apiKey: String,
        @Header("accept") accept: String = "application/json"
    ): JsonObject
}

interface AemetApiClient {
    @GET
    suspend fun request(
        @Url url: String,
        @Query("api_key") apiKey: String
    ): JsonObject

    @GET
    suspend fun data(
        @Url url: String
    ): JsonElement
}

object WeatherApiFields {
    const val CURRENT =
        "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,rain,weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m"
    const val HOURLY =
        "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation_probability,precipitation,rain,weather_code,cloud_cover,pressure_msl,visibility,wind_speed_10m,wind_direction_10m,uv_index"
    const val DAILY =
        "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,rain_sum,wind_speed_10m_max,wind_direction_10m_dominant,sunrise,sunset,uv_index_max"
}

object MarineApiFields {
    const val HOURLY = "sea_surface_temperature,wave_height,wave_direction,wave_period"
}

data class GeocodingResponse(
    val results: List<GeocodingResult>?
)

data class GeocodingResult(
    val id: Long?,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String?,
    @com.google.gson.annotations.SerializedName("country_code")
    val countryCode: String?,
    val admin1: String?,
    val timezone: String?
)
