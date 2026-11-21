package com.andebugulin.awareen.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.andebugulin.awareen.R
import kotlin.math.abs

/**
 * A line over consecutive days with one highlighted point (the day being
 * looked at) and a dashed average line. Tapping near a point reports its
 * index through [onPointTap].
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val MAX_DOTS = 31
    }

    var onPointTap: ((Int) -> Unit)? = null

    private var values: List<Int> = emptyList()
    private var labels: List<String> = emptyList()
    private var selected = -1
    private var formatValue: (Int) -> String = { it.toString() }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private val lineColor = ContextCompat.getColor(context, R.color.accent_primary)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.control_track)
        strokeWidth = dp(1f)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
        textAlign = Paint.Align.CENTER
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val valuePaint = Paint(labelPaint).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
    }
    private val path = Path()
    private val labelBg = android.graphics.RectF()

    fun setData(newValues: List<Int>, newLabels: List<String>, selectedIndex: Int, format: (Int) -> String) {
        values = newValues
        labels = newLabels
        selected = selectedIndex
        formatValue = format
        invalidate()
    }

    private fun xAt(i: Int): Float {
        val pad = dp(14f)
        return if (values.size < 2) width / 2f else pad + (width - 2 * pad) * i / (values.size - 1)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        val top = valuePaint.textSize + dp(10f)
        val bottom = height - labelPaint.textSize - dp(10f)
        fun yAt(v: Int) = bottom - (bottom - top) * v / max

        // Soft fill under the line, fading towards the baseline.
        fillPaint.shader = LinearGradient(0f, top, 0f, bottom,
            (lineColor and 0x00FFFFFF) or 0x40000000, lineColor and 0x00FFFFFF, Shader.TileMode.CLAMP)
        path.reset()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(xAt(i), yAt(v)) else path.lineTo(xAt(i), yAt(v)) }
        val line = Path(path)
        path.lineTo(xAt(values.size - 1), bottom)
        path.lineTo(xAt(0), bottom)
        path.close()
        canvas.drawPath(path, fillPaint)

        val nonZero = values.filter { it > 0 }
        if (nonZero.isNotEmpty()) {
            val avgY = yAt(nonZero.sum() / nonZero.size)
            canvas.drawLine(xAt(0), avgY, xAt(values.size - 1), avgY, avgPaint)
        }
        // A thin guide down from the selected point, so it's clear which day the value belongs to.
        if (selected in values.indices) canvas.drawLine(xAt(selected), top, xAt(selected), bottom, guidePaint)
        canvas.drawPath(line, linePaint)

        // Beyond a month the dots would merge into a bead chain; then only the selected one is drawn.
        values.forEachIndexed { i, v ->
            if (i != selected && values.size > MAX_DOTS) return@forEachIndexed
            val r = if (i == selected) dp(5.5f) else dp(3f)
            if (i == selected) canvas.drawCircle(xAt(i), yAt(v), r + dp(2.5f), ringPaint)
            canvas.drawCircle(xAt(i), yAt(v), r, dotPaint)
        }

        // Labels: first, last and the selected day; an end label close to the selected one is left out.
        setOf(0, values.size - 1, selected).filter { it in labels.indices }
            .filter { it == selected || selected !in labels.indices || abs(xAt(it) - xAt(selected)) > dp(44f) }
            .forEach { i ->
            val x = xAt(i).coerceIn(dp(18f), width - dp(18f))
            canvas.drawText(labels[i], x, height - dp(2f), if (i == selected) valuePaint else labelPaint)
        }
        if (selected in values.indices) {
            val text = formatValue(values[selected])
            val half = valuePaint.measureText(text) / 2f + dp(5f)
            val x = xAt(selected).coerceIn(half, width - half)
            val baseline = (yAt(values[selected]) - dp(12f)).coerceAtLeast(valuePaint.textSize + dp(2f))
            // A surface-colored chip keeps the value readable where the line runs through it.
            labelBg.set(x - half, baseline - valuePaint.textSize - dp(1f), x + half, baseline + dp(4f))
            canvas.drawRoundRect(labelBg, dp(4f), dp(4f), ringPaint)
            canvas.drawText(text, x, baseline, valuePaint)
        }
    }

    private var selectedAtDown = -1
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    /**
     * Touching or sliding along the chart selects the nearest day and shows its
     * value right there. Tapping the day that is already selected opens it
     * ([onPointTap]).
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (values.isEmpty()) return super.onTouchEvent(event)
        val nearest = values.indices.minByOrNull { abs(xAt(it) - event.x) } ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selectedAtDown = selected
                downX = event.x
                downY = event.y
                select(nearest)
            }
            MotionEvent.ACTION_MOVE -> {
                // Sliding sideways scrubs through the days; sliding up or down still scrolls the page.
                val dx = abs(event.x - downX)
                if (dx > touchSlop && dx > abs(event.y - downY)) parent?.requestDisallowInterceptTouchEvent(true)
                select(nearest)
            }
            MotionEvent.ACTION_UP -> {
                if (nearest == selectedAtDown) onPointTap?.invoke(nearest) else select(nearest)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun select(i: Int) {
        if (i == selected) return
        selected = i
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
    }
}
