# Weather App

An Android weather app built with Jetpack Compose. Supports multiple locations, multiple forecast providers, a home screen widget, and marine conditions.

## Features

- **Multi-location** — add and switch between any number of locations
- **Multi-provider** — choose a default per location, with sequential fallback; Compare checks each available source
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

## Refresh and widget behavior

Cached content appears immediately. Opening/resuming Forecasts or switching location/tab checks data older than 30 minutes. Refresh, pull-to-refresh, and Retry update only the current location and tab. Marine has its own cache and status. Background work checks saved locations every three hours, skipping recent downloads; transient failures retry with exponential backoff for at most three attempts per cycle.

Valid empty marine responses show “No marine data for this location” and are remembered for 30 minutes; manual refresh can check again immediately. Failed or empty responses retain any previously successful marine cache. A fresh fallback shows its own successful update time, while the Forecast tab explains the preferred source's failure separately. Unexpected storage failures produce scoped feedback and preserve coroutine cancellation.

The resizable widget targets five by two launcher cells (320 × 160dp content). Each location shows current conditions and three locally dated forecast days. Layout selection measures device text sizes: a compact layout puts the name and update status on one line to retain both locations in shorter widgets. Sizes that cannot fit the content offer an app-opening action and ask to enlarge the widget. Larger text may require more space. Only changes affecting displayed weather or its refresh state trigger widget-update notifications. Local date/age redraws run every 30 minutes; network refresh and redraw timing remain subject to Android scheduling.

Room version 7 migrates existing version 5 or 6 data without clearing locations, preferences, or forecasts. Location replacements advance a revision so earlier requests cannot overwrite the replacement's cache. The 6→7 migration adds the marine-unavailable timestamp while preserving prior marine status and data.

## Verification

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lint :app:compileDebugAndroidTestKotlin :app:assembleDebug
```

If Dropbox or another sync tool locks generated files, set an output folder outside the synced tree:

```powershell
.\gradlew.bat "-Pweather.externalBuildRoot=$env:TEMP\weather-verification" :app:testDebugUnitTest :app:lint :app:compileDebugAndroidTestKotlin :app:assembleDebug
```

The implementation verification passed 33 unit tests, lint with zero errors (39 warnings), APK assembly, and instrumentation-test compilation. Instrumentation execution and launcher checks are pending: no device or configured emulator was available. With a device connected, run `:app:connectedDebugAndroidTest`, then check widget placement/resizing and rotation, enlarged fonts, repeated location taps, offline recovery, app/widget synchronization, and background refresh. Instrumentation covers the 5→7 and 6→7 migrations, transactional location changes, cache retention, marine unavailability, navigation, fallback feedback, compact widget content, and live Glance updates.

## Tech Stack

- **Language** — Kotlin
- **UI** — Jetpack Compose, Material Design 3
- **Widget** — Glance AppWidget
- **Database** — Room
- **Networking** — Retrofit + OkHttp
- **Background work** — WorkManager
- **Settings** — DataStore
- **Min SDK** — 26 (Android 8.0)
