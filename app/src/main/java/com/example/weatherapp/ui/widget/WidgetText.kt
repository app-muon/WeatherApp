package com.example.weatherapp.ui.widget

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.RemoteViews
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.layout.height
import com.example.weatherapp.R

private fun styledWidgetText(text: String, style: WidgetTextStyle): CharSequence = SpannableString(text).apply {
    val span = when (style.font) {
        WidgetFont.Bold -> StyleSpan(Typeface.BOLD)
        WidgetFont.Medium -> TypefaceSpan("sans-serif-medium")
        WidgetFont.Normal -> null
    }
    if (span != null) setSpan(span, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

/** Measures the very same TextView, spans and scaled pixel sizes sent to the launcher. */
internal fun measureWidgetLayout(context: Context): WidgetLayoutMetrics = object : WidgetLayoutMetrics {
    private val display = context.resources.displayMetrics
    override val density = display.density
    private val sizes = mutableMapOf<Pair<String, WidgetTextStyle>, WidgetTextSize>()
    override fun sizePx(style: WidgetTextStyle) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, style.sp, display)
    override fun measure(text: String, style: WidgetTextStyle): WidgetTextSize = sizes.getOrPut(text to style) {
        val view = LayoutInflater.from(context).inflate(R.layout.widget_compact_text, FrameLayout(context), false) as TextView
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx(style))
        view.text = styledWidgetText(text, style)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)
        WidgetTextSize(view.measuredWidth / density, view.measuredHeight / density)
    }
}

@Composable
internal fun WidgetText(
    text: String,
    style: WidgetTextStyle,
    metrics: WidgetLayoutMetrics,
    color: Int,
    modifier: GlanceModifier = GlanceModifier,
    alignEnd: Boolean = false,
    description: String? = null,
    height: Float = metrics.measure(text, style).height
) {
    val views = RemoteViews(LocalContext.current.packageName, R.layout.widget_compact_text).apply {
        setTextViewText(R.id.widget_text, styledWidgetText(text, style))
        // Resolve SP with the current configuration, including Android's nonlinear font scaling.
        setTextViewTextSize(R.id.widget_text, TypedValue.COMPLEX_UNIT_PX, metrics.sizePx(style))
        setTextColor(R.id.widget_text, color)
        setInt(R.id.widget_text, "setGravity", (if (alignEnd) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL)
        if (description != null) setContentDescription(R.id.widget_text, description)
    }
    AndroidRemoteViews(views, modifier.height(height.dp))
}
