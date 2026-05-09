package com.example.weatherapp.data.provider

import com.example.weatherapp.data.api.AemetApiClient
import com.example.weatherapp.data.api.MetOfficeApiClient
import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.domain.model.ProviderForecast
import com.example.weatherapp.domain.model.CurrentWeather
import com.example.weatherapp.domain.model.DailyForecast
import com.example.weatherapp.domain.model.HourlyForecast
import com.example.weatherapp.domain.model.WeatherUnits
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MetOfficeProvider(
    private val api: MetOfficeApiClient,
    private val apiKey: String,
    private val gson: Gson
) : WeatherProvider {
    override val id = WeatherProviderIds.MET_OFFICE
    override val displayName = "Met Office"
    override val shortName = "MO"
    override val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    override suspend fun isAvailableFor(location: LocationEntity): Boolean =
        isConfigured && location.countryCodeOrName() == "GB"

    override suspend fun fetchForecast(location: LocationEntity, units: WeatherUnits): ProviderFetchResult {
        val hourly = api.pointForecast(
            url = "sitespecific/v0/point/hourly?excludeParameterMetadata=true&includeLocationName=true&latitude=${location.latitude}&longitude=${location.longitude}",
            apiKey = apiKey
        )
        val daily = api.pointForecast(
            url = "sitespecific/v0/point/daily?excludeParameterMetadata=true&includeLocationName=true&latitude=${location.latitude}&longitude=${location.longitude}",
            apiKey = apiKey
        )
        val forecast = parseForecast(location, hourly, daily)
        val raw = JsonObject().apply {
            add("hourly", hourly)
            add("daily", daily)
        }
        return ProviderFetchResult(
            forecast = forecast,
            rawJson = gson.toJson(raw)
        )
    }

    private fun parseForecast(location: LocationEntity, hourlyRoot: JsonObject, dailyRoot: JsonObject): ProviderForecast {
        val zone = location.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("Europe/London")
        val hourly = hourlyRoot.metOfficeTimeSeries().mapNotNull { it.toMetOfficeHourly(zone) }
        val daily = dailyRoot.metOfficeTimeSeries()
            .mapNotNull { it.toMetOfficeDaily(zone) }
            .filterNot { it.date.isBefore(LocalDate.now(zone)) }
            .take(7)
        return ProviderForecast(
            providerId = id,
            providerName = displayName,
            locationId = location.id,
            fetchedAt = Instant.now(),
            current = hourly.currentForecast(),
            hourly = hourly,
            daily = daily,
            attribution = "Met Office DataHub"
        )
    }
}

class AemetProvider(
    private val api: AemetApiClient,
    private val apiKey: String,
    private val gson: Gson
) : WeatherProvider {
    private val municipalityMutex = Mutex()
    private var municipalityCache: List<AemetMunicipality>? = null
    override val id = WeatherProviderIds.AEMET
    override val displayName = "AEMET"
    override val shortName = "AEMET"
    override val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    override suspend fun isAvailableFor(location: LocationEntity): Boolean =
        isConfigured && location.countryCodeOrName() == "ES"

    override suspend fun fetchForecast(location: LocationEntity, units: WeatherUnits): ProviderFetchResult {
        val municipality = findNearestMunicipality(location)
        val dailyPayload = aemetPayload("opendata/api/prediccion/especifica/municipio/diaria/${municipality.forecastCode}/")
        val hourlyPayload = aemetPayload("opendata/api/prediccion/especifica/municipio/horaria/${municipality.forecastCode}/")
        val forecast = parseForecast(location, dailyPayload, hourlyPayload)
        val raw = JsonObject().apply {
            addProperty("municipalityId", municipality.id)
            addProperty("forecastCode", municipality.forecastCode)
            addProperty("municipalityName", municipality.name)
            add("daily", dailyPayload)
            add("hourly", hourlyPayload)
        }
        return ProviderFetchResult(
            forecast = forecast,
            rawJson = gson.toJson(raw)
        )
    }

    private suspend fun aemetPayload(url: String): JsonElement {
        val response = api.request(url = url, apiKey = apiKey)
        val description = response.stringOrNull("descripcion") ?: response.stringOrNull("estado")
        val dataUrl = response.stringOrNull("datos")
            ?: throw IllegalStateException(
                "AEMET response did not include datos URL" + description?.let { ": $it" }.orEmpty()
            )
        return api.data(dataUrl)
    }

    private suspend fun findNearestMunicipality(location: LocationEntity): AemetMunicipality {
        val municipalities = municipalityMutex.withLock { municipalityCache }
            ?: municipalityMutex.withLock {
                municipalityCache ?: run {
                    val payload = aemetPayload("opendata/api/maestro/municipios")
                    val list = payload.asArray().mapNotNull { element ->
                        val obj = element.asObjectOrNull() ?: return@mapNotNull null
                        val id = obj.stringOrNull("id") ?: return@mapNotNull null
                        val forecastCode = id.filter { it.isDigit() }.takeLast(5).padStart(5, '0')
                            .takeIf { it.length == 5 } ?: return@mapNotNull null
                        val name = obj.stringOrNull("nombre") ?: obj.stringOrNull("capital") ?: id
                        val lat = obj.doubleOrNull("latitud_dec") ?: return@mapNotNull null
                        val lon = obj.doubleOrNull("longitud_dec") ?: return@mapNotNull null
                        AemetMunicipality(id = id, forecastCode = forecastCode, name = name, latitude = lat, longitude = lon)
                    }
                    municipalityCache = list
                    list
                }
            }
        return municipalities.minByOrNull { it.distanceTo(location.latitude, location.longitude) }
            ?: throw IllegalStateException("AEMET municipality list was empty")
    }

    private fun parseForecast(
        location: LocationEntity,
        dailyPayload: JsonElement,
        hourlyPayload: JsonElement
    ): ProviderForecast {
        val zone = location.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("Europe/Madrid")
        val dailyRoot = dailyPayload.asArray().firstOrNull()?.asObjectOrNull()
        val hourlyRoot = hourlyPayload.asArray().firstOrNull()?.asObjectOrNull()
        val daily = dailyRoot?.getAsJsonObject("prediccion")
            ?.arrayOrEmpty("dia")
            ?.mapNotNull { it.asObjectOrNull()?.let { day -> parseDaily(day, zone) } }
            .orEmpty()
        val hourly = hourlyRoot?.getAsJsonObject("prediccion")
            ?.arrayOrEmpty("dia")
            ?.flatMap { it.asObjectOrNull()?.let { day -> parseHourlyDay(day, zone) }.orEmpty() }
            .orEmpty()
        return ProviderForecast(
            providerId = id,
            providerName = displayName,
            locationId = location.id,
            fetchedAt = Instant.now(),
            current = hourly.firstOrNull()?.toCurrent(),
            hourly = hourly,
            daily = daily,
            attribution = "AEMET OpenData"
        )
    }

    private fun parseDaily(day: JsonObject, zone: ZoneId): DailyForecast? {
        val date = day.dateOrNull("fecha") ?: return null
        val temperature = day.objOrNull("temperatura")
        val wind = day.arrayOrEmpty("viento").bestPeriod()
        return DailyForecast(
            date = date,
            weatherCode = day.arrayOrEmpty("estadoCielo").bestPeriod()?.stringOrNull("value")?.toAemetWeatherCode(),
            tempMin = temperature?.doubleOrNull("minima"),
            tempMax = temperature?.doubleOrNull("maxima"),
            precipitationProbabilityMax = day.arrayOrEmpty("probPrecipitacion")
                .mapNotNull { it.asObjectOrNull()?.intOrNull("value") }
                .maxOrNull(),
            precipitationSum = null,
            rainSum = null,
            windSpeedMax = wind?.doubleOrNull("velocidad"),
            windDirectionDominant = wind?.stringOrNull("direccion")?.compassDegrees(),
            sunrise = null,
            sunset = null,
            uvIndexMax = day.doubleOrNull("uvMax")
        )
    }

    private fun parseHourlyDay(day: JsonObject, zone: ZoneId): List<HourlyForecast> {
        val date = day.dateOrNull("fecha") ?: return emptyList()
        val temperatures = day.arrayOrEmpty("temperatura")
        val feelsLike = day.arrayOrEmpty("sensTermica")
        val humidity = day.arrayOrEmpty("humedadRelativa")
        val precipitationProbability = day.arrayOrEmpty("probPrecipitacion")
        val precipitation = day.arrayOrEmpty("precipitacion")
        val skies = day.arrayOrEmpty("estadoCielo")
        val wind = day.arrayOrEmpty("vientoAndRachaMax")
        return temperatures.mapNotNull { element ->
            val temp = element.asObjectOrNull() ?: return@mapNotNull null
            val hour = temp.stringOrNull("periodo")?.toIntOrNull() ?: return@mapNotNull null
            val time = ZonedDateTime.of(date, LocalTime.of(hour.coerceIn(0, 23), 0), zone)
            HourlyForecast(
                time = time,
                temperature = temp.doubleOrNull("value"),
                feelsLike = feelsLike.valueForPeriod(hour)?.doubleOrNull("value"),
                humidity = humidity.valueForPeriod(hour)?.intOrNull("value"),
                precipitationProbability = precipitationProbability.valueForPeriod(hour)?.intOrNull("value"),
                precipitation = precipitation.valueForPeriod(hour)?.doubleOrNull("value"),
                rain = precipitation.valueForPeriod(hour)?.doubleOrNull("value"),
                weatherCode = skies.valueForPeriod(hour)?.stringOrNull("value")?.toAemetWeatherCode(),
                cloudCover = null,
                pressure = null,
                visibility = null,
                windSpeed = wind.valueForPeriod(hour)?.doubleOrNull("velocidad") ?: wind.valueForPeriod(hour)?.doubleOrNull("value"),
                windDirection = wind.valueForPeriod(hour)?.stringOrNull("direccion")?.compassDegrees(),
                uvIndex = null
            )
        }
    }
}

private data class AemetMunicipality(
    val id: String,
    val forecastCode: String,
    val name: String,
    val latitude: Double,
    val longitude: Double
) {
    fun distanceTo(latitude: Double, longitude: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(latitude - this.latitude)
        val dLon = Math.toRadians(longitude - this.longitude)
        val originLat = Math.toRadians(this.latitude)
        val targetLat = Math.toRadians(latitude)
        val a = sin(dLat / 2).pow(2) + sin(dLon / 2).pow(2) * cos(originLat) * cos(targetLat)
        return 2 * earthRadiusKm * asin(sqrt(a))
    }
}

private fun HourlyForecast.toCurrent() = CurrentWeather(
    time = time,
    temperature = temperature,
    feelsLike = feelsLike,
    humidity = humidity,
    precipitation = precipitation,
    rain = rain,
    weatherCode = weatherCode,
    cloudCover = cloudCover,
    pressure = pressure,
    windSpeed = windSpeed,
    windDirection = windDirection
)

private fun List<HourlyForecast>.currentForecast(): CurrentWeather? {
    if (isEmpty()) return null
    val now = ZonedDateTime.now(first().time.zone)
    return minByOrNull { kotlin.math.abs(java.time.Duration.between(now, it.time).toMinutes()) }?.toCurrent()
}

private fun JsonObject.metOfficeTimeSeries(): List<JsonObject> =
    arrayOrEmpty("features")
        .firstOrNull()
        ?.asObjectOrNull()
        ?.objOrNull("properties")
        ?.arrayOrEmpty("timeSeries")
        ?.mapNotNull { it.asObjectOrNull() }
        .orEmpty()

private fun JsonObject.toMetOfficeHourly(zone: ZoneId): HourlyForecast? {
    val time = zonedTimeOrNull("time", zone) ?: return null
    return HourlyForecast(
        time = time,
        temperature = firstDoubleOrNull("screenTemperature", "screenTemp", "screenAirTemperature"),
        feelsLike = firstDoubleOrNull("feelsLikeTemperature", "feelsLikeTemp", "feelsLikeScreenTemperature"),
        humidity = firstDoubleOrNull("screenRelativeHumidity", "relativeHumidity")?.roundToInt(),
        precipitationProbability = firstIntOrNull("probOfPrecipitation", "probabilityOfPrecipitation"),
        precipitation = firstDoubleOrNull("totalPrecipAmount", "totalPrecipitationAmount"),
        rain = firstDoubleOrNull("totalPrecipAmount", "totalPrecipitationAmount"),
        weatherCode = firstIntOrNull("significantWeatherCode", "weatherCode")?.toOpenMeteoWeatherCode(),
        cloudCover = null,
        pressure = doubleOrNull("mslp")?.let { if (it > 2000) it / 100 else it },
        visibility = doubleOrNull("visibility"),
        windSpeed = firstDoubleOrNull("windSpeed10m", "windSpeed")?.msToKmh(),
        windDirection = firstIntOrNull("windDirectionFrom10m", "windDirection10m", "windDirection"),
        uvIndex = doubleOrNull("uvIndex")
    )
}

private fun JsonObject.toMetOfficeDaily(zone: ZoneId): DailyForecast? {
    val date = zonedTimeOrNull("time", zone)?.toLocalDate() ?: dateOrNull("time") ?: return null
    val middayWind = doubleOrNull("midday10MWindSpeed")
    val midnightWind = doubleOrNull("midnight10MWindSpeed")
    return DailyForecast(
        date = date,
        weatherCode = (
            firstIntOrNull("daySignificantWeatherCode", "dayWeatherCode")
                ?: firstIntOrNull("nightSignificantWeatherCode", "nightWeatherCode")
                ?: firstIntOrNull("significantWeatherCode", "weatherCode")
            )?.toOpenMeteoWeatherCode(),
        tempMin = firstDoubleOrNull(
            "minScreenAirTemp",
            "minScreenAirTemperature",
            "nightMinScreenAirTemp",
            "nightMinScreenTemperature",
            "minScreenTemperature"
        ),
        tempMax = firstDoubleOrNull(
            "maxScreenAirTemp",
            "maxScreenAirTemperature",
            "dayMaxScreenAirTemp",
            "dayMaxScreenTemperature",
            "maxScreenTemperature"
        ),
        precipitationProbabilityMax = listOfNotNull(
            firstIntOrNull("probOfPrecipitation", "probabilityOfPrecipitation"),
            firstIntOrNull("dayProbabilityOfPrecipitation", "dayProbOfPrecipitation"),
            firstIntOrNull("nightProbabilityOfPrecipitation", "nightProbOfPrecipitation")
        ).maxOrNull(),
        precipitationSum = listOfNotNull(
            firstDoubleOrNull("totalPrecipAmount", "totalPrecipitationAmount"),
            firstDoubleOrNull("dayMaxTotalPrecipAmount", "dayMaxTotalPrecipitationAmount"),
            firstDoubleOrNull("nightMaxTotalPrecipAmount", "nightMaxTotalPrecipitationAmount")
        ).sumOrNull(),
        rainSum = listOfNotNull(
            firstDoubleOrNull("totalPrecipAmount", "totalPrecipitationAmount"),
            firstDoubleOrNull("dayMaxTotalPrecipAmount", "dayMaxTotalPrecipitationAmount"),
            firstDoubleOrNull("nightMaxTotalPrecipAmount", "nightMaxTotalPrecipitationAmount")
        ).sumOrNull(),
        windSpeedMax = listOfNotNull(middayWind, midnightWind, firstDoubleOrNull("windSpeed10m", "windSpeed"))
            .maxOrNull()
            ?.msToKmh(),
        windDirectionDominant = firstIntOrNull("midday10MWindDirection", "middayWindDirection")
            ?: firstIntOrNull("midnight10MWindDirection", "midnightWindDirection")
            ?: firstIntOrNull("windDirectionFrom10m", "windDirection10m", "windDirection"),
        sunrise = null,
        sunset = null,
        uvIndexMax = doubleOrNull("uvIndex")
    )
}

private fun JsonObject.zonedTimeOrNull(name: String, zone: ZoneId): ZonedDateTime? =
    stringOrNull(name)?.let { raw ->
        runCatching { Instant.parse(raw).atZone(zone) }
            .getOrNull()
            ?: runCatching { ZonedDateTime.parse(raw).withZoneSameInstant(zone) }.getOrNull()
    }

private fun Double.msToKmh(): Double = this * 3.6

private fun List<Double>.sumOrNull(): Double? = if (isEmpty()) null else sum()

private fun Int.toOpenMeteoWeatherCode(): Int? = when (this) {
    0, 1 -> 0
    2, 3 -> 2
    5, 6 -> 45
    7, 8 -> 3
    9, 10, 11, 12 -> 61
    13, 14, 15 -> 65
    16, 17, 18 -> 71
    19, 20, 21 -> 96
    22, 23, 24 -> 71
    25, 26, 27 -> 75
    28, 29, 30 -> 95
    else -> null
}

private fun JsonElement.asArray(): JsonArray = when {
    isJsonArray -> asJsonArray
    isJsonObject && asJsonObject.has("datos") -> JsonArray()
    else -> JsonArray()
}

private fun JsonElement.asObjectOrNull(): JsonObject? =
    if (isJsonObject) asJsonObject else null

private fun JsonObject.arrayOrEmpty(name: String): JsonArray =
    if (has(name) && !get(name).isJsonNull && get(name).isJsonArray) getAsJsonArray(name) else JsonArray()

private fun JsonObject.objOrNull(name: String): JsonObject? =
    if (has(name) && !get(name).isJsonNull && get(name).isJsonObject) getAsJsonObject(name) else null

private fun JsonObject.stringOrNull(name: String): String? =
    if (has(name) && !get(name).isJsonNull) runCatching { get(name).asString }.getOrNull() else null

private fun JsonObject.doubleOrNull(name: String): Double? =
    if (has(name) && !get(name).isJsonNull && stringOrNull(name)?.isNotBlank() == true) {
        runCatching { get(name).asDouble }.getOrNull()
            ?: stringOrNull(name)?.let { raw -> runCatching { raw.replace(',', '.').toDouble() }.getOrNull() }
    } else {
        null
    }

private fun JsonObject.intOrNull(name: String): Int? =
    if (has(name) && !get(name).isJsonNull && stringOrNull(name)?.isNotBlank() == true) {
        runCatching { get(name).asInt }.getOrNull()
    } else {
        null
    }

private fun JsonObject.dateOrNull(name: String): LocalDate? =
    stringOrNull(name)?.substringBefore("T")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

private fun JsonObject.firstDoubleOrNull(vararg names: String): Double? =
    names.firstNotNullOfOrNull { doubleOrNull(it) }

private fun JsonObject.firstIntOrNull(vararg names: String): Int? =
    names.firstNotNullOfOrNull { intOrNull(it) }

private fun JsonArray.bestPeriod(): JsonObject? =
    mapNotNull { it.asObjectOrNull() }
        .firstOrNull { it.stringOrNull("periodo") == "00-24" }
        ?: mapNotNull { it.asObjectOrNull() }.firstOrNull { it.stringOrNull("value").isNullOrBlank().not() }

private fun JsonArray.valueForPeriod(hour: Int): JsonObject? {
    val period = "%02d".format(hour)
    return mapNotNull { it.asObjectOrNull() }.firstOrNull { it.stringOrNull("periodo") == period }
}

private fun String.toAemetWeatherCode(): Int? = trim().takeIf { it.isNotBlank() }?.let { raw ->
    val code = raw.takeWhile { it.isDigit() }.toIntOrNull() ?: return@let null
    when (code) {
        in 11..12 -> 0
        13, 17 -> 2
        in 14..16 -> 3
        in 23..26, in 43..46, in 61..66 -> 61
        in 33..36, in 51..56, in 71..78 -> 71
        in 81..82 -> 45
        in 95..99 -> 95
        else -> 3
    }
}

private fun String.compassDegrees(): Int? = when (uppercase()) {
    "N" -> 0
    "NE" -> 45
    "E" -> 90
    "SE" -> 135
    "S" -> 180
    "SW" -> 225
    "W", "O" -> 270
    "NW", "NO" -> 315
    else -> null
}
