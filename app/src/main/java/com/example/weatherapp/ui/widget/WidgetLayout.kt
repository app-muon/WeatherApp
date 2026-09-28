package com.example.weatherapp.ui.widget

import kotlin.math.ceil
import kotlin.math.floor

internal enum class WidgetFont { Normal, Bold, Medium }
internal data class WidgetTextStyle(val sp: Float, val font: WidgetFont = WidgetFont.Normal)
internal data class WidgetTextSize(val width: Float, val height: Float)

internal interface WidgetLayoutMetrics {
    val density: Float
    fun measure(text: String, style: WidgetTextStyle): WidgetTextSize
    fun sizePx(style: WidgetTextStyle): Float
}

internal data class WidgetTypography(val date: WidgetTextStyle, val iconSize: Float) {
    val name = WidgetTextStyle(13f, WidgetFont.Bold)
    val status = WidgetTextStyle(10f)
    val value = WidgetTextStyle(12f, WidgetFont.Medium)
    val action = WidgetTextStyle(12f)
}

internal enum class AddLocationAction { None, Label, Plus }

internal data class WidgetLayoutSpec(
    val visibleLocations: Int,
    val showForecast: Boolean,
    val abbreviatedDays: Boolean,
    val typography: WidgetTypography,
    val innerPadding: Float,
    val horizontalPadding: Float,
    val columnWidths: List<Float>,
    val headerHeight: Float,
    val dateHeight: Float,
    val valueHeight: Float,
    val requiredHeight: Float,
    val addLocation: AddLocationAction = AddLocationAction.None,
    val rowGap: Float = 2f,
    val columnGap: Float = 2f,
    val currentInHeader: Boolean = false,
    val inlineHeadings: Boolean = false
)

/** Content takes priority over spacious styling. A hidden location never displaces a complete row. */
internal fun chooseWidgetLayout(
    width: Float,
    height: Float,
    metrics: WidgetLayoutMetrics,
    items: List<WidgetLocationContent>
): WidgetLayoutSpec {
    val roomy = WidgetTypography(WidgetTextStyle(10f), 18f)
    val dense = WidgetTypography(WidgetTextStyle(9f), 16f)
    fun pixels(dp: Float) = floor(dp.coerceAtLeast(0f) * metrics.density + .001f) / metrics.density
    fun candidate(count: Int, full: Boolean, abbreviated: Boolean, type: WidgetTypography, padding: Float, inlineHeadings: Boolean = false): WidgetLayoutSpec? {
        // Keep glyphs inside launcher-enforced rounded corners without adding vertical inset.
        val horizontalPadding = 8 + padding
        val available = pixels(width - 6 - 2 * horizontalPadding)
        val currentWidth = pixels(available * .2f)
        val dayWidth = pixels((available - currentWidth) / 3)
        val preferredColumns = if (full) listOf(currentWidth, dayWidth, dayWidth, available - currentWidth - 2 * dayWidth)
            else listOf(available)
        val visible = items.take(count)
        val cells = visible.flatMap { if (full) it.cells else it.cells.take(1) }
        fun cellWidth(cell: WidgetCellContent): Float {
            val label = cell.heading(abbreviated)
            val heading = if (label.isEmpty()) 0f else metrics.measure(label, type.date).width
            val temperature = metrics.measure(cell.temperature, type.value).width
            val weather = type.iconSize + temperature
            val width = 2 + if (inlineHeadings) heading + (if (label.isEmpty()) 0 else 2) + weather else maxOf(heading, weather)
            return ceil(width * metrics.density) / metrics.density
        }
        val minimumColumns = preferredColumns.indices.map { index -> visible.maxOfOrNull { cellWidth(it.cells[index]) } ?: 0f }
        // Borrow spare width when a timestamp or temperature exceeds its preferred column.
        // Every layout keeps the weather symbols and the complete displayed values.
        if (minimumColumns.sum() > available) return null
        val spare = available - minimumColumns.sum()
        val desiredSpare = preferredColumns.zip(minimumColumns) { preferred, minimum -> (preferred - minimum).coerceAtLeast(0f) }
        val totalSpare = desiredSpare.sum()
        val columns = minimumColumns.mapIndexed { index, minimum ->
            pixels(minimum + if (totalSpare > 0) spare * desiredSpare[index] / totalSpare else 0f)
        }.toMutableList()
        columns[columns.lastIndex] = available - columns.dropLast(1).sum()
        val header = visible.maxOfOrNull {
            maxOf(metrics.measure(it.name, type.name).height, metrics.measure(it.status, type.status).height)
        } ?: metrics.measure("Choose locations", type.action).height
        val headingHeight = cells.filter { it.heading(abbreviated).isNotEmpty() }
            .maxOfOrNull { metrics.measure(it.heading(abbreviated), type.date).height } ?: 0f
        val date = if (inlineHeadings) 0f else headingHeight
        val value = maxOf(type.iconSize, if (inlineHeadings) headingHeight else 0f,
            cells.maxOfOrNull { metrics.measure(it.temperature, type.value).height } ?: 0f)
        val required = 6 + padding * 2 + count * (header + date + value) + (count - 1).coerceAtLeast(0) * 2
        val fitsWidth = visible.all { item ->
            (if (full) item.cells else item.cells.take(1)).withIndex().all { (index, cell) ->
                cellWidth(cell) <= columns[index]
            }
        }
        if (!fitsWidth || required > height) return null
        val actionHeight = metrics.measure("Add second location", type.action).height
        val add = when {
            items.size != 1 -> AddLocationAction.None
            required + 2 + actionHeight <= height && metrics.measure("Add second location", type.action).width <= available -> AddLocationAction.Label
            else -> AddLocationAction.Plus
        }
        val contentHeight = required + if (add == AddLocationAction.Label) 2 + actionHeight else 0f
        val gapCount = (count - 1).coerceAtLeast(0) + if (add == AddLocationAction.Label) 1 else 0
        val extraSpacing = if (count > 0) pixels((height - contentHeight) / (2 + gapCount)) else 0f
        return WidgetLayoutSpec(count, full, abbreviated, type, padding + extraSpacing, horizontalPadding, columns, header, date, value,
            contentHeight + extraSpacing * (2 + gapCount), add, rowGap = 2 + extraSpacing, inlineHeadings = inlineHeadings)
    }

    for (count in items.size.coerceAtMost(2) downTo 1) {
        for ((type, padding) in listOf(roomy to 4f, dense to 0f)) {
            for (abbreviated in listOf(false, true)) {
                candidate(count, true, abbreviated, type, padding)?.let { return it }
            }
        }
        // Two lines per location: header, then short weekdays, weather symbols and temperatures.
        // Today's forecast needs no heading. Try this before removing the second location.
        candidate(count, true, true, dense, 0f, inlineHeadings = true)?.let { return it }
    }
    for ((type, padding) in listOf(roomy to 4f, dense to 0f)) {
        candidate(items.size.coerceAtMost(1), false, false, type, padding)?.let { return it }
    }
    // Very small hosts still get a clickable current-conditions row. No font-size overrides.
    val header = maxOf(metrics.measure(items.firstOrNull()?.name.orEmpty(), dense.name).height,
        metrics.measure(items.firstOrNull()?.status.orEmpty(), dense.status).height)
    val value = maxOf(16f, metrics.measure(items.firstOrNull()?.cells?.first()?.temperature.orEmpty(), dense.value).height)
    val inline = header + value + 6 > height
    return WidgetLayoutSpec(items.size.coerceAtMost(1), false, false, dense, 0f, 8f,
        listOf(pixels(width - 22)), header, 0f,
        value, 6 + if (inline) maxOf(header, value) else header + value,
        if (items.size == 1) AddLocationAction.Plus else AddLocationAction.None, currentInHeader = inline)
}
