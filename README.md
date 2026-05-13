# Weather App

An Android weather app built with Jetpack Compose. Supports multiple locations, multiple forecast providers, a home screen widget, and marine conditions.

## Features

- **Multi-location** — add and switch between any number of locations
- **Multi-provider** — fetches from up to 5 sources simultaneously; choose a default per location
- **Forecast views** — current conditions, 48-hour hourly, 7-day daily, marine conditions
- **Compare mode** — side-by-side comparison across providers for any day
- **Home screen widget** — shows current conditions and a 3-day outlook for up to 2 locations
- **Background refresh** — WorkManager refreshes stale data automatically
- **Units** — temperature (°C / °F), wind speed (km/h / mph), precipitation (mm / in)

## Forecast Providers

| Provider | Coverage | API key required |
|---|---|---|
| [Open-Meteo](https://open-meteo.com) | Global | No |
| [Yr / MET Norway](https://api.met.no) | Global | No |
| [Met Office](https://datahub.metoffice.gov.uk) | UK only | Yes |
| [AEMET](https://opendata.aemet.es) | Spain only | Yes |

## Setup

### API keys

Create a `local.properties` file in the project root (alongside `settings.gradle.kts`) and add keys for any paid providers you want to use:

```
MET_OFFICE_API_KEY=your_key_here
AEMET_API_KEY=your_key_here
```

The app builds and runs without these — those providers will simply be unavailable.

### Build

Open in Android Studio and run, or build from the command line:

```bash
./gradlew assembleDebug
```

Requires Android Studio Meerkat or later (AGP 9, Kotlin 2.0).

## Tech Stack

- **Language** — Kotlin
- **UI** — Jetpack Compose, Material Design 3
- **Widget** — Glance AppWidget
- **Database** — Room
- **Networking** — Retrofit + OkHttp
- **Background work** — WorkManager
- **Settings** — DataStore
- **Min SDK** — 26 (Android 8.0)
