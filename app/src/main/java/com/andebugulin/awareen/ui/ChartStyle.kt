package com.andebugulin.awareen.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.andebugulin.awareen.R

/**
 * What the day view and the overview share: one duration format, one app
 * palette, and the app donut with its list, so both read the same way.
 */
object ChartStyle {

    fun formatDuration(seconds: Int): String {
        if (seconds < 60) return if (seconds <= 0) "0m" else "<1m"
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    private val PALETTE = listOf(R.color.app_1, R.color.app_2, R.color.app_3, R.color.app_4,
        R.color.app_5, R.color.app_6, R.color.app_7, R.color.app_8)

    /**
     * Donut of [apps] (label → seconds, largest first): the first eight in
     * their own color, the rest as one "other" slice. Below it one row per app
     * with time and share; [maxRows] cuts the list, ending in an "N more" row.
     */
    fun showApps(
        activity: Activity, chart: DaySplitChartView, list: LinearLayout,
        apps: List<Pair<String, Int>>, maxRows: Int = Int.MAX_VALUE,
    ) {
        val palette = PALETTE.map { ContextCompat.getColor(activity, it) }
        val other = ContextCompat.getColor(activity, R.color.app_other)
        val sum = apps.sumOf { it.second }.coerceAtLeast(1)
        val rest = apps.drop(palette.size).sumOf { it.second }
        val segments = apps.take(palette.size).mapIndexed { i, (_, s) -> DaySegment(s, palette[i]) } +
            listOfNotNull(rest.takeIf { it > 0 }?.let { DaySegment(it, other) })
        chart.setData(segments, apps.size.toString(), if (apps.size == 1) "app" else "apps")

        list.removeAllViews()
        fun addRow(label: String, value: String, color: Int) {
            val row = activity.layoutInflater.inflate(R.layout.item_day_split_legend, list, false)
            row.findViewById<View>(R.id.legendDot).backgroundTintList = ColorStateList.valueOf(color)
            row.findViewById<TextView>(R.id.legendLabel).text = label
            row.findViewById<TextView>(R.id.legendValue).text = value
            list.addView(row)
        }
        apps.take(maxRows).forEachIndexed { i, (label, seconds) ->
            val share = seconds * 100.0 / sum
            addRow(label, "${formatDuration(seconds)}  ·  ${if (share < 1) "<1" else share.toInt()}%",
                palette.getOrElse(i) { other })
        }
        if (apps.size > maxRows) {
            val more = apps.drop(maxRows)
            addRow("${more.size} more apps", formatDuration(more.sumOf { it.second }), other)
        }
    }
}
