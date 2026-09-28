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

The resizable widget targets five by one launcher cells. Both locations, current conditions and three forecast days fit with weather symbols at **320 × 96dp**, or **320 × 72dp with short weekdays beside the symbols and temperatures**, at normal font scale with typical values. Launcher cell dimensions vary. Today's forecast has no heading; future days use locally determined dates, shortened to three-letter weekdays when space is tight. The location name and right-aligned update status share one header at every size. Layout selection measures the displayed strings using the same unpadded TextView and scaled fonts as the widget. Larger sizes add padding and larger dates/icons. Short widgets try two lines per location before hiding a location, retaining a symbol for every visible weather cell. Columns can share spare width to accommodate longer timestamps or temperatures. Spare height is distributed above, between and below the rows. Only when both complete forecasts still cannot fit does the first remain visible with `(+1)` for the hidden location. Still smaller sizes show current conditions and retain the action opening that location. One configured location offers “Add second location”, or a compact `+` opening Locations when space is limited. Font preferences are preserved, so enlarged fonts may show less content. The minimum resize height remains 48dp, with no maximum height.

Status distinguishes successful downloads (`Updated 09:20`), active updates, failures, old data and a healthy fallback (`Yr fallback · 09:20`). Downloads from a different local day include the date. Current conditions retain their separate observation/estimate timestamp; missing data stays unavailable. Accessibility descriptions include the full source and timestamps. Only changes affecting displayed weather or its refresh state trigger widget-update notifications. Local date/age redraws run every 30 minutes; network refresh and redraw timing remain subject to Android scheduling.

Room version 7 migrates existing version 5 or 6 data without clearing locations, preferences, or forecasts. Location replacements advance a revision so earlier requests cannot overwrite the replacement's cache. The 6→7 migration adds the marine-unavailable timestamp while preserving prior marine status and data.

## Verification

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lint :app:compileDebugAndroidTestKotlin :app:assembleDebug
```

If Dropbox or another sync tool locks generated files, set an output folder outside the synced tree:

```powershell
.\gradlew.bat "-Pweather.externalBuildRoot=$env:TEMP\weather-verification" :app:testDebugUnitTest :app:lint :app:compileDebugAndroidTestKotlin :app:assembleDebug
```

Verification passed 40 unit tests, all 25 instrumentation tests on a Pixel 8 emulator (API 37), lint with zero errors (39 existing warnings), APK assembly, and instrumentation-test compilation. Rendered RemoteViews checks cover both complete forecasts at 320 × 72/80/96/120/160dp with normal fonts and at 440 × 96dp with 1.3× fonts, plus reduced-content layouts at smaller sizes and 1.3×/2× fonts. They also cover weather symbols and their bounds, balanced vertical spacing, long accented names, negative/three-digit temperatures, missing data, fallback/failure states, local dates, right-aligned status, launcher corner clipping, and the coloured border. Test renderings are exported through the instrumentation runner's additional test output directory.

The emulator's Pixel Launcher was also checked with live Tokyo and London forecasts: placement, resizing to one cell, landscape mode, 1.3×/2× fonts, location-opening taps, and accessibility descriptions. Its four-column grid initially placed a 4 × 2 widget, which resized successfully to 4 × 1; the metadata's 5 × 1 target is subject to launcher cell dimensions. Physical-device/OEM launcher checks and spoken TalkBack output remain pending. With a device connected, run `:app:connectedDebugAndroidTest`. Existing migration, transactional location, cache-retention, marine, navigation, fallback, and live-composition regression coverage remains in place.

## Tech Stack

- **Language** — Kotlin
- **UI** — Jetpack Compose, Material Design 3
- **Widget** — Glance AppWidget
- **Database** — Room
- **Networking** — Retrofit + OkHttp
- **Background work** — WorkManager
- **Settings** — DataStore
- **Min SDK** — 26 (Android 8.0)
