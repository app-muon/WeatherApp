package com.example.weatherapp.ui.widget

import android.content.Context
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal enum class WidgetLayout { Full, Compact, Enlarge }

internal data class WidgetLayoutMetrics(
    val nameHeight: Float,
    val statusHeight: Float,
    val dateHeight: Float,
    val valueHeight: Float,
    val columnWidth: Float,
    val addLocationHeight: Float
) {
    fun requiredHeight(rows: Int, compact: Boolean): Float {
        val heading = if (compact) maxOf(nameHeight, statusHeight) else nameHeight + statusHeight
        val rowHeight = heading + dateHeight + maxOf(18f, valueHeight)
        return 16 + rows * rowHeight + (rows - 1).coerceAtLeast(0) * 6 +
            if (rows == 1) 6 + addLocationHeight else 0f
    }

    val requiredWidth: Float get() = 16 + 4 * (columnWidth + 2)
}

internal fun chooseWidgetLayout(width: Float, height: Float, metrics: WidgetLayoutMetrics, rows: Int = 2): WidgetLayout = when {
    width < metrics.requiredWidth -> WidgetLayout.Enlarge
    height >= metrics.requiredHeight(rows, compact = false) -> WidgetLayout.Full
    height >= metrics.requiredHeight(rows, compact = true) -> WidgetLayout.Compact
    else -> WidgetLayout.Enlarge
}

/** Use device font metrics, including font padding and accessibility scaling, rather than the XML minimum size. */
internal fun measureWidgetLayout(context: Context): WidgetLayoutMetrics {
    val display = context.resources.displayMetrics
    fun paint(sp: Float, bold: Boolean = false) = TextPaint().apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, display)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
    fun TextPaint.height() = (fontMetricsInt.bottom - fontMetricsInt.top) / display.density
    fun TextPaint.width(text: String) = measureText(text) / display.density
    val name = paint(13f, bold = true)
    val status = paint(10f)
    val value = paint(12f)
    val dateLabels = (0..6).map { LocalDate.of(2026, 12, 24).plusDays(it.toLong()).format(DateTimeFormatter.ofPattern("EEE d")) }
    val dateWidth = (dateLabels + "28/12 23:59").maxOf { status.width(it) }
    return WidgetLayoutMetrics(name.height(), status.height(), status.height(), value.height(),
        maxOf(18 + value.width("-88°/-88°"), dateWidth), paint(14f).height() + 8)
}
