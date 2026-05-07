# Android Weather App Spec

## Goal

Build a native Android weather app with a home-screen widget. The app uses free weather APIs, stores two user-selected locations, shows compact current/daily weather in a widget, and opens into a detailed forecast screen with hourly and daily forecasts.

## Tech stack

Use:

- Kotlin
- Jetpack Compose
- Glance App Widget
- Room
- WorkManager
- Retrofit or Ktor Client
- Kotlin Coroutines
- DataStore for settings

Use Open-Meteo as the primary API. It requires no API key.

## Core user flow

On first launch, the user is prompted to add two locations.

The user searches by city/place name. The app uses Open-Meteo Geocoding API to resolve the search into latitude/longitude/timezone/country. The user selects the correct result.

Once two locations are saved, the app fetches weather for both and caches it locally.

The Android widget shows two rows, one per saved location.

Tapping the widget opens the app. Tapping a specific row should open the detailed screen for that location if feasible. Otherwise, open the main app with the first location selected.

## Stored data

Create a locations table:

```kotlin
LocationEntity(
    id: Long,
    name: String,
    country: String?,
    adminArea: String?,
    latitude: Double,
    longitude: Double,
    timezone: String?,
    displayOrder: Int
)
```

Create a forecast_cache table:

```kotlin
ForecastCacheEntity(
    locationId: Long,
    fetchedAtEpochMillis: Long,
    rawJson: String
)
```

Settings in DataStore:

```kotlin
WeatherSettings(
    temperatureUnit: "celsius" | "fahrenheit",
    windSpeedUnit: "kmh" | "mph",
    precipitationUnit: "mm" | "inch"
)
```

Default to Celsius, km/h, and mm.

## API integration

### Location search

Use:

```text
GET https://geocoding-api.open-meteo.com/v1/search?name={query}&count=10&language=en&format=json
```

Show results using:

- name
- admin1
- country
- latitude
- longitude
- timezone

### Forecast request

For each saved location, call:

```text
GET https://api.open-meteo.com/v1/forecast
```

Required parameters:

```text
latitude={lat}
longitude={lon}
timezone=auto
forecast_days=7
current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,rain,weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m
hourly=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation_probability,precipitation,rain,weather_code,cloud_cover,pressure_msl,visibility,wind_speed_10m,wind_direction_10m,uv_index
daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,rain_sum,wind_speed_10m_max,wind_direction_10m_dominant,sunrise,sunset,uv_index_max
temperature_unit=celsius
wind_speed_unit=kmh
precipitation_unit=mm
```

Use settings to switch units in the request.

## Weather code mapping

Create a local mapper from Open-Meteo weather_code to:

```kotlin
WeatherCondition(
    code: Int,
    label: String,
    icon: WeatherIcon
)
```

Minimum labels:

- 0 Clear
- 1 Mainly clear
- 2 Partly cloudy
- 3 Overcast
- 45 Fog
- 48 Rime fog
- 51 Light drizzle
- 53 Drizzle
- 55 Heavy drizzle
- 61 Light rain
- 63 Rain
- 65 Heavy rain
- 71 Light snow
- 73 Snow
- 75 Heavy snow
- 80 Light showers
- 81 Showers
- 82 Heavy showers
- 95 Thunderstorm
- 96 Thunderstorm with hail
- 99 Thunderstorm with heavy hail

Use simple vector icons or bundled drawable assets. Do not depend on emoji for the production UI.

## Widget spec

Create a Glance home-screen widget.

The widget displays exactly two rows if two locations exist.

Each row layout:

```text
Location name | Current | Today | Tomorrow | T+2
```

Each cell:

Current:

- weather icon
- current temperature

Daily forecast cells:

- weather icon
- min temp / max temp

Example:

```text
London      icon 13 degrees   icon 8 degrees/15 degrees   icon 7 degrees/14 degrees   icon 9 degrees/17 degrees
Manchester  icon 11 degrees   icon 6 degrees/13 degrees   icon 7 degrees/14 degrees   icon 8 degrees/15 degrees
```

Widget requirements:

- The widget must use cached forecast data from Room.
- The widget must not fetch directly from the API inside the widget rendering code.
- If no locations are configured, show: `Tap to choose locations`
- If one location is configured, show one row and a second row saying: `Add second location`
- If cached data is older than 6 hours, show a subtle stale indicator in the widget, e.g. refresh/stale indicator.

Click behaviour:

- Tapping row 1 opens the app to location 1 details.
- Tapping row 2 opens the app to location 2 details.
- Use a deep link or activity intent with: `locationId={id}`

## App screens

### 1. Setup screen

Shown when fewer than two locations exist.

Features:

- Search box.
- List of geocoding results.
- Button to save location.
- Ability to replace location 1 or location 2.

### 2. Main forecast screen

The main screen has a location switcher at the top.

For MVP, use tabs or a segmented control:

```text
London | Manchester
```

Selecting a location changes all forecast content.

The selected location should also be determined by the widget row click.

### 3. Current weather panel

Show:

- Location name
- Current temperature
- Weather label
- Feels like
- Humidity
- Wind speed and direction
- Rain now
- Precipitation probability for current hour, if available
- Pressure
- Cloud cover
- UV index
- Last updated

### 4. Hourly forecast section

This is required.

Show at least the next 24 hours by default.

Preferably allow horizontal scrolling across the next 48 hours.

Each hourly card should show:

- Time
- Weather icon
- Temperature
- Feels like
- Precipitation probability
- Rain amount
- Wind speed

Example:

```text
14:00
icon
13 degrees
Feels 11 degrees
Rain 40%
Wind 12 km/h
```

Group hourly forecasts by day if displaying more than 24 hours.

The app should use the location's local timezone returned by the API.

### 5. Daily forecast section

Show 7 days.

Each daily card should show:

- Day/date
- Weather icon
- Min/max temperature
- Precipitation probability max
- Rain total
- Max wind speed
- UV index max
- Sunrise
- Sunset

Tapping a daily card expands it to show more detail and the hourly forecasts for that day.

## Refresh and caching

Use Room as the source of truth.

Repository flow:

- UI observes Room
- Repository fetches API
- Repository writes new cache to Room
- UI updates from Room
- Widget updates from Room

Refresh triggers:

- On app launch.
- On pull-to-refresh.
- After changing locations.
- Periodic background refresh every 3 hours using WorkManager.

Before rendering widget, schedule/trigger a refresh if cache is older than 6 hours, but still render cached data immediately.

Do not block the widget on network calls.

## Error handling

If API request fails but cache exists:

- Show cached data.
- Display: `Last updated: {time}`
- Display: `Could not refresh forecast`

If API request fails and no cache exists:

- Show an error state with retry.

If geocoding returns no results:

- Show: `No locations found`

If user has fewer than two locations:

- Prompt setup but allow app use with one location.

## App architecture

Suggested packages:

- data/api
- data/db
- data/repository
- domain/model
- domain/mapper
- ui/setup
- ui/forecast
- ui/widget
- worker
- settings

Main classes:

- WeatherApiClient
- GeocodingApiClient
- WeatherRepository
- LocationRepository
- ForecastParser
- WeatherCodeMapper
- ForecastRefreshWorker
- WeatherWidget
- MainActivity

## Domain models

Use parsed domain models rather than passing raw API response into UI.

```kotlin
CurrentWeather(
    time: ZonedDateTime,
    temperature: Double,
    feelsLike: Double,
    humidity: Int,
    precipitation: Double,
    rain: Double,
    weatherCode: Int,
    cloudCover: Int,
    pressure: Double,
    windSpeed: Double,
    windDirection: Int
)

HourlyForecast(
    time: ZonedDateTime,
    temperature: Double,
    feelsLike: Double,
    humidity: Int,
    precipitationProbability: Int?,
    precipitation: Double,
    rain: Double,
    weatherCode: Int,
    cloudCover: Int,
    pressure: Double,
    visibility: Double?,
    windSpeed: Double,
    windDirection: Int,
    uvIndex: Double?
)

DailyForecast(
    date: LocalDate,
    weatherCode: Int,
    tempMin: Double,
    tempMax: Double,
    precipitationProbabilityMax: Int?,
    precipitationSum: Double,
    rainSum: Double,
    windSpeedMax: Double,
    windDirectionDominant: Int?,
    sunrise: ZonedDateTime?,
    sunset: ZonedDateTime?,
    uvIndexMax: Double?
)

Forecast(
    locationId: Long,
    fetchedAt: Instant,
    current: CurrentWeather,
    hourly: List<HourlyForecast>,
    daily: List<DailyForecast>
)
```

## Acceptance criteria

- The app must allow the user to save two locations.
- The widget must show two rows, one per location.
- Each widget row must show current weather, today, tomorrow, and T+2.
- Each daily widget cell must show an icon and min/max temperature.
- Tapping a widget row must open the app to that location.
- The app must show current detailed weather.
- The app must show hourly forecasts for at least the next 24 hours.
- The app must show 7 daily forecasts.
- The app must cache forecasts locally.
- The widget must render from cache, not direct network calls.
- The app must refresh weather in the background at least every 3 hours.
- The app must still show cached data when offline.

