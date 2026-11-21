package com.andebugulin.awareen.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.andebugulin.awareen.R
import kotlin.math.abs

/**
 * Vertical bars, e.g. screen time per hour of a day or per day of a range.
 * Every bar gets a slot, only every [labelEvery]-th slot a label under it.
 * The tallest bar's value is written above it, which replaces a y axis.
 *
 * Touching or sliding along the bars selects one: its value appears above it
 * and its label below, the others dim. Tapping the selected bar again calls
 * [onBarTap] (e.g. to open that day).
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onBarTap: ((Int) -> Unit)? = null

    private var values: List<Int> = emptyList()
    private var labels: List<String> = emptyList()
    private var labelEvery = 3
    private var formatValue: (Int) -> String = { it.toString() }
    private var selected = -1

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private val barColor = ContextCompat.getColor(context, R.color.day_screen)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.control_track)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
        textAlign = Paint.Align.CENTER
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val peakPaint = Paint(labelPaint).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface)
    }
    private val rect = RectF()

    /** [selectedIndex] starts selected (e.g. the day being looked at); -1 labels the tallest bar. */
    fun setData(newValues: List<Int>, newLabels: List<String>, every: Int, format: (Int) -> String, selectedIndex: Int = -1) {
        values = newValues
        labels = newLabels
        labelEvery = every.coerceAtLeast(1)
        formatValue = format
        selected = selectedIndex.takeIf { it in newValues.indices } ?: -1
        invalidate()
    }

    private fun slot() = width.toFloat() / values.size.coerceAtLeast(1)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1
        val top = peakPaint.textSize + dp(6f)
        val bottom = height - labelPaint.textSize - dp(8f)
        val slot = slot()
        val gap = (slot * 0.25f).coerceAtMost(dp(4f))
        val radius = dp(2f).coerceAtMost((slot - gap) / 2f)
        val shown = if (selected >= 0) selected else values.indexOf(values.max())

        values.forEachIndexed { i, v ->
            val left = i * slot + gap / 2f
            val right = (i + 1) * slot - gap / 2f
            // A faint full-height track keeps the grid readable where nothing happened.
            rect.set(left, top, right, bottom)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            if (v > 0) {
                val h = ((bottom - top) * v / max).coerceAtLeast(dp(2f))
                rect.set(left, bottom - h, right, bottom)
                barPaint.alpha = if (selected < 0 || i == selected) 255 else 110
                canvas.drawRoundRect(rect, radius, radius, barPaint)
            }
            val labelled = i < labels.size && (if (selected >= 0) i == selected else i % labelEvery == 0)
            // With a selection, keep the regular labels that don't collide with the selected one.
            val regular = selected >= 0 && i % labelEvery == 0 && abs(i - selected) * slot > dp(36f)
            if (i < labels.size && (labelled || regular)) {
                val x = ((left + right) / 2f).coerceIn(dp(16f), width - dp(16f))
                canvas.drawText(labels[i], x, height - dp(2f), if (i == selected) peakPaint else labelPaint)
            }
        }
        if (shown >= 0 && (values[shown] > 0 || selected >= 0)) {
            val text = formatValue(values[shown])
            val half = peakPaint.measureText(text) / 2f + dp(4f)
            val x = ((shown + 0.5f) * slot).coerceIn(half, width - half)
            val barTop = bottom - (bottom - top) * values[shown] / max
            val baseline = (barTop - dp(4f)).coerceAtLeast(peakPaint.textSize)
            rect.set(x - half, baseline - peakPaint.textSize, x + half, baseline + dp(3f))
            canvas.drawRoundRect(rect, dp(4f), dp(4f), chipPaint)
            canvas.drawText(text, x, baseline, peakPaint)
        }
    }

    private var selectedAtDown = -1
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (values.isEmpty()) return super.onTouchEvent(event)
        val index = (event.x / slot()).toInt().coerceIn(0, values.size - 1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selectedAtDown = selected
                downX = event.x
                downY = event.y
                select(index)
            }
            MotionEvent.ACTION_MOVE -> {
                // Sliding sideways scrubs through the bars; sliding up or down still scrolls the page.
                val dx = abs(event.x - downX)
                if (dx > touchSlop && dx > abs(event.y - downY)) parent?.requestDisallowInterceptTouchEvent(true)
                select(index)
            }
            MotionEvent.ACTION_UP -> {
                if (index == selectedAtDown) onBarTap?.invoke(index)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun select(i: Int) {
        if (i == selected) return
        selected = i
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
    }
}
