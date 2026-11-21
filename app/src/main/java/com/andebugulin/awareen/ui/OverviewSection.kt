package com.andebugulin.awareen.ui

import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.andebugulin.awareen.R
import com.andebugulin.awareen.data.AppUsageRepository
import com.andebugulin.awareen.data.ScreenTimeRepository
import com.andebugulin.awareen.domain.AnalyticsKeys
import com.andebugulin.awareen.domain.TrackingDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The Analytics screen's "Overview": every tracking day of a range (7 days,
 * 30 days, all time) at once — totals, a line of all days, the apps as a donut
 * with a list, the average day hour by hour and the average per weekday.
 * Days count from the reset time like everywhere else (see TrackingDay).
 */
class OverviewSection(
    private val activity: AppCompatActivity,
    private val repo: ScreenTimeRepository,
    private val appUsage: AppUsageRepository,
    private val resetHour: Int,
    private val today: LocalDate,
) {
    /** Number of days back from today; null for everything recorded. */
    private var rangeDays: Int? = 30
    private val worker = Executors.newSingleThreadExecutor()
    private val labels = mutableMapOf<String, String>()   // package → app name, looked up once

    /** Remembers small view choices (line or bars); separate from Awareen's own data and settings. */
    private val uiPrefs = activity.getSharedPreferences("analytics_ui", android.content.Context.MODE_PRIVATE)

    private class Overview(
        val start: LocalDate,
        val totals: List<Int>,
        val apps: List<Pair<String, Int>>,
        val hourAverage: Map<Int, Int>,
        val weekdayAverage: Map<DayOfWeek, Int>,
    ) {
        val activeDays get() = totals.count { it > 0 }
        val total get() = totals.sum()
    }

    private val rangeButtons = listOf(
        R.id.range7Button to 7,
        R.id.range30Button to 30,
        R.id.rangeAllButton to null,
    )

    init {
        rangeButtons.forEach { (id, days) ->
            activity.findViewById<Button>(id).setOnClickListener { select(days) }
        }
        activity.findViewById<Button>(R.id.trendLineButton).setOnClickListener { showTrendAsBars(false) }
        activity.findViewById<Button>(R.id.trendBarsButton).setOnClickListener { showTrendAsBars(true) }
        showTrendAsBars(uiPrefs.getBoolean(KEY_TREND_BARS, false))
        activity.findViewById<DaySplitChartView>(R.id.overviewAppsChart).apply {
            setTextColors(ContextCompat.getColor(activity, R.color.text_primary),
                ContextCompat.getColor(activity, R.color.text_secondary))
            setValueTypeface(androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.fraunces))
        }
    }

    private fun showTrendAsBars(bars: Boolean) {
        uiPrefs.edit().putBoolean(KEY_TREND_BARS, bars).apply()
        activity.findViewById<View>(R.id.overviewTrendChart).visibility = if (bars) View.GONE else View.VISIBLE
        activity.findViewById<View>(R.id.overviewTrendBars).visibility = if (bars) View.VISIBLE else View.GONE
        activity.findViewById<Button>(R.id.trendLineButton).setTextColor(ContextCompat.getColor(activity,
            if (bars) R.color.text_muted else R.color.accent_primary))
        activity.findViewById<Button>(R.id.trendBarsButton).setTextColor(ContextCompat.getColor(activity,
            if (bars) R.color.accent_primary else R.color.text_muted))
    }

    fun show() = select(rangeDays)

    fun close() = worker.shutdownNow()

    private fun select(days: Int?) {
        rangeDays = days
        rangeButtons.forEach { (id, d) ->
            activity.findViewById<Button>(id).setTextColor(ContextCompat.getColor(activity,
                if (d == days) R.color.accent_primary else R.color.text_muted))
        }
        worker.execute {
            val data = load(days)
            activity.runOnUiThread { if (!activity.isDestroyed && rangeDays == days) render(data) }
        }
    }

    private fun load(days: Int?): Overview {
        val recorded = repo.getTrackingDays(resetHour).toMap()
        val start = when {
            days != null -> today.minusDays(days - 1L)
            recorded.isEmpty() -> today
            else -> recorded.keys.min()
        }
        val dates = generateSequence(start) { it.plusDays(1) }.takeWhile { it <= today }.toList()
        val totals = dates.map { recorded[it] ?: 0 }
        val active = dates.filter { (recorded[it] ?: 0) > 0 }

        val hourSums = mutableMapOf<Int, Int>()
        val appSums = mutableMapOf<String, Int>()
        active.forEach { date ->
            repo.getTrackingDayHours(date, resetHour).forEach { (h, s) -> hourSums[h] = (hourSums[h] ?: 0) + s }
            appUsage.getAppUsage(AnalyticsKeys.analyticsDateKey(date)).forEach { (pkg, s) ->
                // Two packages can share a label; show them as one.
                val label = labels.getOrPut(pkg) { appUsage.appLabel(pkg) }
                appSums[label] = (appSums[label] ?: 0) + s
            }
        }
        // Averages over days with any screen time, so days before tracking started don't count as 0.
        val n = active.size.coerceAtLeast(1)
        val weekday = active.groupBy { it.dayOfWeek }
            .mapValues { (_, ds) -> ds.sumOf { recorded[it] ?: 0 } / ds.size }

        return Overview(
            start = start,
            totals = totals,
            apps = appSums.toList().sortedByDescending { it.second },
            hourAverage = hourSums.mapValues { it.value / n },
            weekdayAverage = weekday,
        )
    }

    private fun render(o: Overview) {
        val fmt = ChartStyle::formatDuration
        activity.findViewById<TextView>(R.id.overviewTotal).text = fmt(o.total)
        activity.findViewById<TextView>(R.id.overviewAverage).text =
            if (o.activeDays == 0) "–" else fmt(o.total / o.activeDays)
        activity.findViewById<TextView>(R.id.overviewDays).text = o.activeDays.toString()

        // Day by day, as a line or as bars; the busiest day starts selected.
        val dayLabel = DateTimeFormatter.ofPattern("d.M.", Locale.ENGLISH)
        val dayLabels = o.totals.indices.map { o.start.plusDays(it.toLong()).format(dayLabel) }
        val busiest = o.totals.indices.maxByOrNull { o.totals[it] }?.takeIf { o.totals[it] > 0 } ?: -1
        val openDay = { i: Int -> DayActivity.open(activity, o.start.plusDays(i.toLong())) }
        activity.findViewById<LineChartView>(R.id.overviewTrendChart).apply {
            setData(o.totals, dayLabels, busiest, fmt)
            onPointTap = openDay
        }
        activity.findViewById<BarChartView>(R.id.overviewTrendBars).apply {
            setData(o.totals, dayLabels, (o.totals.size / 6).coerceAtLeast(1), fmt, busiest)
            onBarTap = openDay
        }

        // Apps
        val chart = activity.findViewById<DaySplitChartView>(R.id.overviewAppsChart)
        val list = activity.findViewById<LinearLayout>(R.id.overviewAppsList)
        val message = activity.findViewById<TextView>(R.id.overviewAppsMessage)
        if (o.apps.isEmpty()) {
            chart.visibility = View.GONE
            list.removeAllViews()
            message.visibility = View.VISIBLE
            message.text = if (appUsage.hasPermission()) "No app use recorded in this range."
            else "Allow usage access below to see which apps your time went to."
        } else {
            chart.visibility = View.VISIBLE
            message.visibility = View.GONE
            ChartStyle.showApps(activity, chart, list, o.apps, maxRows = APP_ROWS)
        }

        // The average day, hour by hour, starting at the reset hour.
        val order = TrackingDay.hourOrder(resetHour)
        activity.findViewById<BarChartView>(R.id.overviewHoursChart)
            .setData(order.map { o.hourAverage[it] ?: 0 }, order.map { "%02d".format(it) }, 3, fmt)
        val peak = o.hourAverage.maxByOrNull { it.value }
        activity.findViewById<TextView>(R.id.overviewHoursInsight).text =
            if (peak == null || peak.value == 0) "Not enough data yet."
            else "On an average day you're on screen most around %02d:00 (${fmt(peak.value)} in that hour).".format(peak.key)

        // Average per weekday, Monday first.
        val week = DayOfWeek.values().toList()
        activity.findViewById<BarChartView>(R.id.overviewWeekdayChart)
            .setData(week.map { o.weekdayAverage[it] ?: 0 }, week.map { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }, 1, fmt)
        val most = o.weekdayAverage.maxByOrNull { it.value }
        val least = o.weekdayAverage.minByOrNull { it.value }
        activity.findViewById<TextView>(R.id.overviewWeekdayInsight).text = when {
            most == null || least == null -> "Not enough data yet."
            most.key == least.key -> "Only ${most.key.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}s so far."
            else -> "Most on ${most.key.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}s (${fmt(most.value)}), " +
                "least on ${least.key.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}s (${fmt(least.value)})."
        }
    }

    companion object {
        private const val APP_ROWS = 12
        private const val KEY_TREND_BARS = "trend_as_bars"
    }
}
