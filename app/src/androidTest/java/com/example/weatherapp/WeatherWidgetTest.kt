package com.example.weatherapp

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.runComposition
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.data.repository.*
import com.example.weatherapp.domain.model.*
import com.example.weatherapp.ui.widget.ObserveWeatherWidget
import com.example.weatherapp.ui.widget.measureWidgetLayout
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

@OptIn(ExperimentalGlanceApi::class)
@RunWith(AndroidJUnit4::class)
class WeatherWidgetTest {
    private val now = Instant.parse("2026-09-27T23:30:00Z")

    @Test fun activeCompositionObservesUpdatesAndEachLocationsDates() = runBlocking {
        val items = MutableStateFlow<List<LocationForecast>>(emptyList())
        val activity = MutableStateFlow(RefreshActivity())
        val time = MutableStateFlow(now)
        val widget = observingWidget(items, activity, time)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val frames = Channel<List<String>>(Channel.UNLIMITED)
        val collecting = launch {
            widget.runComposition(context, sizes = listOf(DpSize(400.dp, 240.dp))).collect { views ->
                frames.send(withContext(Dispatchers.Main) {
                    flatten(views.apply(context, FrameLayout(context))).filterIsInstance<TextView>().map { it.text.toString() }
                })
            }
        }
        suspend fun awaitFrame(predicate: (List<String>) -> Boolean) = withTimeout(10000) {
            var frame: List<String>
            do { frame = frames.receive() } while (!predicate(frame))
            frame
        }
        try {
            awaitFrame { "Choose locations" in it }
            items.value = listOf(item(1, "Tokyo", "Asia/Tokyo"), item(2, "Los Angeles", "America/Los_Angeles"))
            val loaded = awaitFrame { "Tokyo" in it && "Los Angeles" in it }
            assertTrue(loaded.any { it.startsWith("Mon 28") })
            assertTrue(loaded.any { it.startsWith("Sun 27") })
            activity.value = RefreshActivity(scopes = mapOf(RefreshKey(1, 0, ContentScope.Forecast) to 1))
            awaitFrame { "Updating…" in it }
            activity.value = RefreshActivity()
            items.value = items.value.map { it.copy(lastRefreshFailed = true) }
            awaitFrame { it.any { text -> text.contains("Update failed") } }
            items.value = listOf(item(1, "Replacement", "UTC"))
            awaitFrame { "Replacement" in it && "Tokyo" !in it && "Add second location" in it }
            time.value = now.plusSeconds(86400)
            awaitFrame { it.any { text -> text.startsWith("Mon 28") } && it.any { text -> text.contains("Old data") } }
        } finally { collecting.cancelAndJoin() }
    }

    @Test fun compactLayoutAndEmptyLayoutHaveWorkingActions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (items in listOf(emptyList(), listOf(item(1, "Tokyo", "Asia/Tokyo")))) {
            val widget = observingWidget(MutableStateFlow(items), MutableStateFlow(RefreshActivity()), MutableStateFlow(now))
            val views = widget.runComposition(context, sizes = listOf(DpSize(320.dp, 96.dp))).first()
            withContext(Dispatchers.Main) {
                val tree = flatten(views.apply(context, FrameLayout(context)))
                val expected = if (items.isEmpty()) "Choose locations" else "Enlarge widget to see both forecasts"
                assertTrue(tree.filterIsInstance<TextView>().any { it.text.toString() == expected })
                assertTrue(tree.any { it.hasOnClickListeners() })
            }
        }
    }

    @Test fun measuredCompactLayoutRetainsBothLocationsAndTheirForecastDates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val metrics = measureWidgetLayout(context)
        val items = listOf(item(1, "Tokyo", "Asia/Tokyo"), item(2, "Los Angeles", "America/Los_Angeles"))
        val widget = observingWidget(MutableStateFlow(items), MutableStateFlow(RefreshActivity()), MutableStateFlow(now))
        val width = kotlin.math.ceil(maxOf(320f, metrics.requiredWidth))
        val height = kotlin.math.ceil(metrics.requiredHeight(2, compact = true))
        val views = widget.runComposition(context, sizes = listOf(DpSize(width.dp, height.dp))).first()
        withContext(Dispatchers.Main) {
            val tree = flatten(views.apply(context, FrameLayout(context)))
            val labels = tree.filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue("Tokyo" in labels)
            assertTrue("Los Angeles" in labels)
            assertFalse(labels.any { it.contains("Enlarge widget") })
            val formatter = java.time.format.DateTimeFormatter.ofPattern("EEE d")
            items.forEach { item ->
                val today = now.atZone(ZoneId.of(item.location.timezone)).toLocalDate()
                repeat(3) { offset -> assertTrue(today.plusDays(offset.toLong()).format(formatter) in labels) }
            }
            assertEquals(2, labels.count { it == "Now" })
            assertTrue(tree.any { it.hasOnClickListeners() })
        }
    }

    private fun observingWidget(
        items: StateFlow<List<LocationForecast>>, activity: StateFlow<RefreshActivity>, time: StateFlow<Instant>
    ) = object : GlanceAppWidget() {
        override val sizeMode = SizeMode.Exact
        override suspend fun provideGlance(context: Context, id: GlanceId) {
            val initial = items.first()
            val metrics = measureWidgetLayout(context)
            provideContent { ObserveWeatherWidget(items, initial, activity, time, metrics) }
        }
    }

    private fun item(id: Long, name: String, zone: String): LocationForecast {
        val location = LocationEntity(id, name, null, null, null, 0.0, 0.0, zone, (id - 1).toInt(), (id - 1).toInt())
        val today = now.atZone(ZoneId.of(zone)).toLocalDate()
        val forecast = ProviderForecast("open_meteo", "Open-Meteo", id, now, null, emptyList(),
            (0..2).map { DailyForecast(today.plusDays(it.toLong()), 2, 10.0, 20.0, null, null, null, null, null, null, null, null) }, null)
        return LocationForecast(location, forecast, listOf(forecast), emptyList(), null, null, "open_meteo")
    }

    private fun flatten(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
}
