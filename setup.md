# Weather App Setup

## Requirements

- Android Studio with Android SDK 36 installed.
- JDK 11 or newer. Android Studio's bundled JBR works.
- Network access for Gradle dependency resolution and weather API calls.

## Clone And Open

1. Clone the repository.
2. Open the project root in Android Studio.
3. Let Gradle sync complete.

## Optional API Keys

The app works without API keys using Open-Meteo and MET Norway/Yr.

Met Office and AEMET providers are hidden unless keys are configured.

### Met Office DataHub

Use this for UK Met Office forecasts.

1. Go to the Met Office Weather DataHub: <https://datahub.metoffice.gov.uk/>
2. Register or log in.
3. Choose a relevant forecast product/plan, such as the site-specific `Global Spot` product.
4. Subscribe to a suitable plan. Met Office currently offers a free `Global Spot` plan with a daily request limit, and paid tiers for higher usage.
5. Copy the API key from the subscription/API details page.

Official Met Office notes:

- Getting started: <https://datahub.metoffice.gov.uk/docs/getting-started>
- API key FAQ: <https://datahub.metoffice.gov.uk/support/faqs>
- Site-specific pricing: <https://datahub.metoffice.gov.uk/pricing/site-specific>

The FAQ says API credentials are provided after subscribing to a product. If you need to find or refresh an existing key, log in, open `My Subscriptions`, choose `Manage`, then use `API details / Refresh secret`.

Pricing note: the site-specific `Global Spot` free plan is listed as up to 360 calls per day. Other Met Office products or higher request volumes may require a paid subscription.

### AEMET OpenData

Use this for Spanish AEMET forecasts.

1. Go to AEMET OpenData: <https://opendata.aemet.es/centrodedescargas/inicio>
2. Select `Obtencion de API Key` / `Obtención de API Key`.
3. Enter your email address.
4. Complete the captcha/check.
5. Submit the form and keep the API key that AEMET provides.

Official AEMET notes:

- AEMET OpenData information: <https://opendata.aemet.es/centrodedescargas/info>
- API key request page: <https://opendata.aemet.es/centrodedescargas/obtencionAPIKey>

Create or edit `local.properties` in the project root:

```properties
MET_OFFICE_API_KEY=your_met_office_datahub_key
AEMET_API_KEY=your_aemet_opendata_key
```

Do not commit `local.properties` or real API keys.

Provider availability:

- Open-Meteo: all locations, no key.
- MET Norway/Yr: all locations, no key.
- Met Office: UK locations only, requires `MET_OFFICE_API_KEY`.
- AEMET: Spain locations only, requires `AEMET_API_KEY`.

## Build

From the project root:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat compileDebugKotlin
```

## Test

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat testDebugUnitTest
```

## Run

Use Android Studio to run the `app` configuration on an emulator or device.

After installing:

1. Add up to two locations in the app.
2. Open `Locations`.
3. Choose the two widget rows. The widget uses each location's default forecast source.
4. Add the Weather widget to the home screen.

The widget renders from Room cache only. Open the app or wait for WorkManager refresh to populate/update cached forecasts.

## Forecast Sources

The app compares available providers for the selected location:

- Open-Meteo
- Yr / MET Norway
- Met Office, when configured and available
- AEMET, when configured and available

The widget uses the same default source selected for each location. Auto prefers national providers where available, then falls back to Open-Meteo and MET Norway.
