package com.andebugulin.awareen.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.andebugulin.awareen.R

/**
 * Vertical bars, e.g. screen time per hour of a day. Every bar gets a slot,
 * only every [labelEvery]-th slot a label under it. The tallest bar's value is
 * written above it, which replaces a y axis.
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var values: List<Int> = emptyList()
    private var labels: List<String> = emptyList()
    private var labelEvery = 3
    private var formatValue: (Int) -> String = { it.toString() }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.day_screen)
    }
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
    private val rect = RectF()

    fun setData(newValues: List<Int>, newLabels: List<String>, every: Int, format: (Int) -> String) {
        values = newValues
        labels = newLabels
        labelEvery = every.coerceAtLeast(1)
        formatValue = format
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1
        val top = peakPaint.textSize + dp(6f)
        val bottom = height - labelPaint.textSize - dp(8f)
        val slot = width.toFloat() / values.size
        val gap = (slot * 0.25f).coerceAtMost(dp(4f))
        val radius = dp(2f)
        val peakIndex = values.indexOf(values.max())

        values.forEachIndexed { i, v ->
            val left = i * slot + gap / 2f
            val right = (i + 1) * slot - gap / 2f
            // A faint full-height track keeps the hour grid readable where nothing happened.
            rect.set(left, top, right, bottom)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            if (v > 0) {
                val h = ((bottom - top) * v / max).coerceAtLeast(dp(2f))
                rect.set(left, bottom - h, right, bottom)
                canvas.drawRoundRect(rect, radius, radius, barPaint)
            }
            if (i % labelEvery == 0 && i < labels.size) {
                canvas.drawText(labels[i], (left + right) / 2f, height - dp(2f), labelPaint)
            }
        }
        if (values[peakIndex] > 0) {
            val x = ((peakIndex + 0.5f) * slot).coerceIn(dp(20f), width - dp(20f))
            val barTop = bottom - (bottom - top) * values[peakIndex] / max
            canvas.drawText(formatValue(values[peakIndex]), x, barTop - dp(4f), peakPaint)
        }
    }
}
