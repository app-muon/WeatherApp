package com.example.weatherapp

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.Gravity
import java.io.File
import kotlin.math.roundToInt
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
import androidx.test.platform.app.InstrumentationRegistry
import com.example.weatherapp.data.db.LocationEntity
import com.example.weatherapp.data.repository.*
import com.example.weatherapp.domain.model.*
import com.example.weatherapp.ui.widget.*
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

    @Test fun activeCompositionObservesUpdatesAndEachLocationsDates() = runBlocking<Unit> {
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
            assertTrue(loaded.any { it.startsWith("Tue 29") })
            assertFalse(loaded.any { it == "Sun 27" })
            activity.value = RefreshActivity(scopes = mapOf(RefreshKey(1, 0, ContentScope.Forecast) to 1))
            awaitFrame { "Updating…" in it }
            activity.value = RefreshActivity()
            items.value = items.value.map { it.copy(lastRefreshFailed = true) }
            awaitFrame { it.any { text -> text.startsWith("Failed ·") } }
            items.value = listOf(item(1, "Replacement", "UTC"))
            awaitFrame { "Replacement" in it && "Tokyo" !in it && "Add second location" in it }
            time.value = now.plusSeconds(86400)
            awaitFrame { it.any { text -> text.startsWith("Tue 29") } && it.any { text -> text.startsWith("Old ·") } }
        } finally { collecting.cancelAndJoin() }
    }

    @Test fun acceptanceSizesRenderBothCompleteForecastsWithoutClipping() = runBlocking {
        val context = configuredContext(1f)
        val items = listOf(item(1, "São José", "Asia/Tokyo"), item(2, "Málaga", "America/Los_Angeles"))
        for (height in listOf(72, 80, 96, 120, 160)) {
            val size = DpSize(320.dp, height.dp)
            val host = render(context, items, size)
            withContext(Dispatchers.Main) {
                assertCompleteRows(host, items, context, size)
                assertTextFits(host, items.map { it.location.name }.toSet())
                assertHeaders(host, items, context, size)
                val visible = texts(host) + weatherSymbols(host)
                val top = visible.minOf { boundsInHost(host, it).top }
                val bottom = host.height - visible.maxOf { boundsInHost(host, it).bottom }
                // Nested RemoteViews round each dp dimension to physical pixels independently.
                assertEquals("Balance spare space above and below forecasts", top.toFloat(), bottom.toFloat(),
                    2 * context.resources.displayMetrics.density)
                saveRendering(context, host, "widget-320x$height")
            }
        }
    }

    @Test fun shortWidgetKeepsBothForecastsAtLargerFontsWhenWidthPermits() = runBlocking {
        val context = configuredContext(1.3f)
        val items = listOf(item(1, "Tokyo", "Asia/Tokyo"), item(2, "London", "Europe/London"))
        val size = DpSize(440.dp, 96.dp)
        val host = render(context, items, size)
        withContext(Dispatchers.Main) {
            assertCompleteRows(host, items, context, size)
            assertTextFits(host)
            assertHeaders(host, items, context, size)
            val spec = chooseWidgetLayout(440f, 96f, measureWidgetLayout(context), items.map { widgetLocationContent(it, false, now) })
            assertTrue(spec.inlineHeadings)
            val temperature = texts(host).first { it.text.toString() == "-5°" }
            assertEquals(measureWidgetLayout(context).sizePx(spec.typography.value), temperature.textSize, .01f)
            saveRendering(context, host, "widget-440x96-font1.3")
        }
    }

    @Test fun longNamesNegativeAndThreeDigitTemperaturesKeepTheirBounds() = runBlocking {
        val context = configuredContext(1f)
        val longName = "Ávila São José Łódź – a location with a deliberately long name"
        val first = item(1, longName, "UTC")
        val second = item(2, "Reykjavík", "UTC").let { it.copy(forecast = it.forecast!!.copy(
            current = it.forecast!!.current!!.copy(temperature = -100.0),
            daily = it.forecast!!.daily.map { day -> day.copy(tempMax = 100.0, tempMin = -99.0) })) }
        for (size in listOf(DpSize(440.dp, 72.dp), DpSize(320.dp, 120.dp), DpSize(440.dp, 120.dp))) {
            val host = render(context, listOf(first, second), size)
            withContext(Dispatchers.Main) {
                assertCompleteRows(host, listOf(first, second), context, size)
                assertTextFits(host, setOf(longName))
                assertHeaders(host, listOf(first, second), context, size)
                assertTrue(texts(host).any { it.text.toString() == "100°/-99°" })
            }
        }
    }

    @Test fun shortNarrowAndLargeFontWidgetsRetainUsefulClickableContent() = runBlocking {
        val items = listOf(item(1, "Tokyo", "Asia/Tokyo"), item(2, "Los Angeles", "America/Los_Angeles"))
        for ((size, scale) in listOf(DpSize(320.dp, 60.dp) to 1f, DpSize(160.dp, 96.dp) to 1f,
            DpSize(320.dp, 96.dp) to 1.3f, DpSize(320.dp, 96.dp) to 2f, DpSize(320.dp, 48.dp) to 2f)) {
            val context = configuredContext(scale)
            val host = render(context, items, size)
            val spec = chooseWidgetLayout(size.width.value, size.height.value, measureWidgetLayout(context),
                items.map { widgetLocationContent(it, false, now) })
            withContext(Dispatchers.Main) {
                val labels = texts(host).map { it.text.toString() }
                assertTrue("Tokyo" in labels)
                assertTrue(" (+1)" in labels)
                assertFalse("Los Angeles" in labels)
                assertTrue(labels.any { it.startsWith("Updated ") })
                assertTrue("-5°" in labels)
                assertTrue(flatten(host).any { it.hasOnClickListeners() })
                assertTextFits(host, setOf("Tokyo"))
                assertEquals(if (spec.showForecast) 4 else 1, spec.columnWidths.size)
                assertEquals(if (spec.showForecast) 3 else 0, labels.count { it == "20°/10°" })
                assertWeatherSymbols(host, if (spec.showForecast) 4 else 1, context)
                val value = texts(host).first { it.text.toString() == "-5°" }
                assertEquals(measureWidgetLayout(context).sizePx(spec.typography.value), value.textSize, .01f)
                saveRendering(context, host, "widget-${size.width.value.toInt()}x${size.height.value.toInt()}-font$scale")
            }
        }
    }

    @Test fun singleLocationAndEmptyStatesKeepLocationsActions() = runBlocking {
        val context = configuredContext(1f)
        for ((items, size, expected) in listOf(
            Triple(emptyList(), DpSize(160.dp, 48.dp), "Choose locations"),
            Triple(listOf(item(1, "Tokyo", "Asia/Tokyo")), DpSize(320.dp, 96.dp), "Add second location"),
            Triple(listOf(item(1, "Tokyo", "Asia/Tokyo")), DpSize(320.dp, 48.dp), "+")
        )) {
            val host = render(context, items, size)
            withContext(Dispatchers.Main) {
                val target = texts(host).single { it.text.toString() == expected }
                assertTrue(generateSequence(target as View) { it.parent as? View }.any { it.hasOnClickListeners() })
                if (items.isNotEmpty()) assertTrue(texts(host).any { it.text.toString() == "-5°" })
                assertTextFits(host)
            }
        }
    }

    @Test fun fallbackFailuresMissingDataAndPreviousDayTimestampsRenderClearly() = runBlocking {
        val context = configuredContext(1f)
        val base = item(1, "Málaga", "Asia/Tokyo")
        val fallback = base.copy(forecast = base.forecast!!.copy(providerId = "met_norway", providerName = "MET Norway",
            current = base.forecast!!.current!!.copy(time = now.minusSeconds(9 * 3600).atZone(ZoneOffset.UTC))))
        for (item in listOf(fallback, fallback.copy(lastRefreshFailed = true), base.copy(forecast = null),
            base.copy(forecast = base.forecast!!.copy(fetchedAt = now.minusSeconds(9 * 3600))))) {
            val host = render(context, listOf(item, item.copy(location = item.location.copy(id = 2, name = "Évora"))), DpSize(320.dp, 72.dp))
            withContext(Dispatchers.Main) {
                assertTextFits(host)
                assertTrue(texts(host).any { it.text.toString() == compactWidgetStatus(item, false, now) })
                if (item.forecast != null) assertTrue(flatten(host).any {
                    it.contentDescription?.toString()?.contains("Downloaded") == true
                })
            }
        }
    }

    @Test fun shortWeekdaysFitBeforeCompleteForecastIsRemoved() = runBlocking {
        val context = configuredContext(1f)
        val rows = listOf(item(1, "Ávila", "UTC"), item(2, "Málaga", "UTC")).map {
            val row = widgetLocationContent(it, false, now)
            row.copy(cells = row.cells.mapIndexed { index, cell ->
                if (index <= 1) cell else cell.copy(label = "Wednesday 30 September", shortLabel = "Wed")
            })
        }
        val widget = object : GlanceAppWidget() {
            override val sizeMode = SizeMode.Exact
            override suspend fun provideGlance(context: Context, id: GlanceId) {
                val metrics = measureWidgetLayout(context)
                provideContent { WeatherWidgetRows(rows, metrics) }
            }
        }
        val size = DpSize(320.dp, 96.dp)
        val views = widget.runComposition(context, sizes = listOf(size)).first()
        withContext(Dispatchers.Main) {
            val host = layoutViews(context, views, size)
            assertEquals(4, texts(host).count { it.text.toString() == "Wed" })
            assertFalse(texts(host).any { it.text.toString() == "Today" || it.text.toString() == "Sun 27" })
            assertWeatherSymbols(host, 8, context)
            assertTextFits(host)
        }
    }

    private fun configuredContext(scale: Float, locale: java.util.Locale = java.util.Locale.UK): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            fontScale = scale
            setLocale(locale)
        })
    }

    private suspend fun render(context: Context, items: List<LocationForecast>, size: DpSize): FrameLayout {
        val widget = observingWidget(MutableStateFlow(items), MutableStateFlow(RefreshActivity()), MutableStateFlow(now))
        val views = widget.runComposition(context, sizes = listOf(size)).first()
        return withContext(Dispatchers.Main) { layoutViews(context, views, size) }
    }

    private fun layoutViews(context: Context, views: android.widget.RemoteViews, size: DpSize): FrameLayout {
            val host = FrameLayout(context)
            host.addView(views.apply(context, host))
            val density = context.resources.displayMetrics.density
            val width = (size.width.value * density).roundToInt()
            val height = (size.height.value * density).roundToInt()
            host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, width, height)
            return host
    }

    private fun texts(host: View) = flatten(host).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }

    private fun weatherSymbols(host: View) = flatten(host).filterIsInstance<ImageView>()
        // Glance also uses ImageViews for click backgrounds; weather symbols have condition labels.
        .filter { it.visibility == View.VISIBLE && it.drawable != null && !it.contentDescription.isNullOrBlank() }

    private fun assertWeatherSymbols(host: FrameLayout, count: Int, context: Context) {
        val symbols = weatherSymbols(host)
        assertEquals("Keep a weather symbol for every visible cell", count, symbols.size)
        val minimumSize = (16 * context.resources.displayMetrics.density).roundToInt()
        symbols.forEach { symbol ->
            assertTrue(symbol.width >= minimumSize && symbol.height >= minimumSize)
            assertContentBounds(host, symbol)
            assertFalse(symbol.contentDescription.isNullOrBlank())
        }
    }

    private fun assertCompleteRows(host: FrameLayout, items: List<LocationForecast>, context: Context, size: DpSize) {
        val rows = items.map { widgetLocationContent(it, false, now) }
        val spec = chooseWidgetLayout(size.width.value, size.height.value, measureWidgetLayout(context), rows)
        assertEquals(2, spec.visibleLocations)
        assertTrue(spec.showForecast)
        val labels = texts(host).map { it.text.toString() }
        rows.forEach { assertTrue(it.name in labels) }
        val headings = rows.flatMap { it.cells.map { cell -> cell.heading(spec.abbreviatedDays) } }.filter { it.isNotEmpty() }
        headings.groupingBy { it }.eachCount().forEach { (label, count) -> assertEquals(label, count, labels.count { it == label }) }
        assertEquals(8, rows.sumOf { it.cells.size })
        assertWeatherSymbols(host, 8, context)
        assertEquals(6, labels.count { it.contains("°/") })
    }

    private fun assertHeaders(host: FrameLayout, items: List<LocationForecast>, context: Context, size: DpSize) {
        val rows = items.map { widgetLocationContent(it, false, now) }
        val spec = chooseWidgetLayout(size.width.value, size.height.value, measureWidgetLayout(context), rows)
        val expectedEnd = host.width - ((3 + spec.horizontalPadding) * context.resources.displayMetrics.density).roundToInt()
        items.forEachIndexed { index, item ->
            val name = texts(host).single { it.text.toString() == item.location.name }
            val status = texts(host).filter { it.text.toString() == rows[index].status }.let {
                if (it.size == 1) it.single() else it[index]
            }
            val nameBounds = boundsInHost(host, name)
            val statusBounds = boundsInHost(host, status)
            assertEquals(expectedEnd.toFloat(), statusBounds.right.toFloat(), 1f)
            assertTrue(nameBounds.right <= statusBounds.left)
            assertEquals(nameBounds.exactCenterY(), statusBounds.exactCenterY(), 2f)
            assertEquals(Gravity.END, status.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
        }
    }

    private fun boundsInHost(host: ViewGroup, view: View) = Rect(0, 0, view.width, view.height).apply {
        host.offsetDescendantRectToMyCoords(view, this)
    }

    private fun assertTextFits(host: FrameLayout, allowedEllipsis: Set<String> = emptySet()) {
        for (view in texts(host)) {
            val text = view.text.toString()
            assertTrue("Zero width: $text", view.width > 0)
            assertContentBounds(host, view)
            val layout = requireNotNull(view.layout)
            assertEquals("Wrapped: $text", 1, layout.lineCount)
            assertTrue("Clipped height: $text", layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
            if (text !in allowedEllipsis) {
                assertEquals("Ellipsized: $text", 0, layout.getEllipsisCount(0))
                assertTrue("Clipped width: $text", layout.getLineWidth(0) <= view.width - view.compoundPaddingLeft - view.compoundPaddingRight + 1)
            }
            val glyphs = Rect()
            view.paint.getTextBounds(text, 0, text.length, glyphs)
            assertTrue("Clipped accents: $text", view.baseline + glyphs.top >= 0 && view.baseline + glyphs.bottom <= view.height)
        }
    }

    private fun assertContentBounds(host: FrameLayout, view: View) {
        val description = if (view is TextView) view.text else view.contentDescription
        val bounds = boundsInHost(host, view)
        assertTrue("Outside host: $description $bounds", bounds.left >= 0 && bounds.top >= 0 && bounds.right <= host.width && bounds.bottom <= host.height)
        var child: View = view
        while (child.parent is ViewGroup && child.parent !== host) {
            val parent = child.parent as ViewGroup
            assertTrue("Clipped by parent: $description", child.left >= 0 && child.top >= 0 && child.right <= parent.width && child.bottom <= parent.height)
            child = parent
        }
    }

    private fun saveRendering(context: Context, host: FrameLayout, name: String) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val radius = minOf(context.resources.getDimension(android.R.dimen.system_app_widget_background_radius), host.height / 2f)
            for (view in texts(host) + weatherSymbols(host)) {
                val glyphs = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(glyphs))
                val position = boundsInHost(host, view)
                for (y in 0 until glyphs.height) for (x in 0 until glyphs.width) {
                    if (android.graphics.Color.alpha(glyphs.getPixel(x, y)) == 0) continue
                    val px = position.left + x + .5f
                    val py = position.top + y + .5f
                    val dx = maxOf(radius - px, px - (host.width - radius), 0f)
                    val dy = maxOf(radius - py, py - (host.height - radius), 0f)
                    assertTrue("Launcher corner clips ${view.contentDescription}", dx * dx + dy * dy <= radius * radius)
                }
                glyphs.recycle()
            }
        }
        val bitmap = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
        host.draw(Canvas(bitmap))
        val border = (2 * context.resources.displayMetrics.density).roundToInt()
        assertEquals("Cyan border", 0xFF00E5FF.toInt(), bitmap.getPixel(0, 0))
        assertEquals("Magenta border", 0xFFFF3DF2.toInt(), bitmap.getPixel(border, border))
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it) }
            ?: context.getExternalFilesDir(null)!!
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
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
        val forecast = ProviderForecast("open_meteo", "Open-Meteo", id, now,
            CurrentWeather(now.minusSeconds(1800).atZone(ZoneId.of(zone)), -5.0, null, null, null, null, 2, null, null, null, null), emptyList(),
            (0..2).map { DailyForecast(today.plusDays(it.toLong()), 2, 10.0, 20.0, null, null, null, null, null, null, null, null) }, null)
        return LocationForecast(location, forecast, listOf(forecast), emptyList(), null, null, "open_meteo")
    }

    private fun flatten(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
}
