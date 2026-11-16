package com.andebugulin.awareen.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.andebugulin.awareen.R
import com.andebugulin.awareen.data.AppUsageRepository
import com.andebugulin.awareen.data.ScreenTimeRepository
import com.andebugulin.awareen.data.SettingsRepository
import com.andebugulin.awareen.domain.AnalyticsKeys
import com.andebugulin.awareen.domain.TrackingDay
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * One tracking day at a time (reset time to reset time, like the counter):
 * which apps it went to (donut and full list), when (bars per hour), and how
 * it compares to the two weeks before it (line). ‹ › and the line's points
 * move between days.
 */
class DayActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DATE = "date"
        private const val TREND_DAYS = 14
        private const val AVERAGE_DAYS = 7

        fun open(context: Context, date: LocalDate) {
            context.startActivity(Intent(context, DayActivity::class.java).putExtra(EXTRA_DATE, date.toString()))
        }
    }

    /** Everything one day shows, loaded off the main thread. */
    private class DayData(
        val total: Int,
        val hours: Map<Int, Int>,
        val apps: List<Pair<String, Int>>,
        val trendStart: LocalDate,
        val trend: List<Int>,
        val average: Int?,
    )

    private lateinit var repo: ScreenTimeRepository
    private lateinit var appUsage: AppUsageRepository
    private var resetHour = 0
    private var resetMinute = 0
    private lateinit var today: LocalDate
    private var firstDay: LocalDate? = null
    private lateinit var date: LocalDate
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var appsChart: DaySplitChartView
    private lateinit var hoursChart: BarChartView
    private lateinit var trendChart: LineChartView

    private val titleFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)
    private val shortFormat = DateTimeFormatter.ofPattern("d.M.", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_day)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.app_background)

        repo = ScreenTimeRepository(this)
        appUsage = AppUsageRepository(this)
        val settings = SettingsRepository(this)
        resetHour = settings.getResetHour()
        resetMinute = settings.getResetMinute()
        today = TrackingDay.dateOf(System.currentTimeMillis(), resetHour, resetMinute, ZoneId.systemDefault())

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        prevButton = findViewById(R.id.prevDayButton)
        nextButton = findViewById(R.id.nextDayButton)
        prevButton.setOnClickListener { show(date.minusDays(1)) }
        nextButton.setOnClickListener { show(date.plusDays(1)) }

        appsChart = findViewById(R.id.appsChart)
        appsChart.setTextColors(
            ContextCompat.getColor(this, R.color.text_primary),
            ContextCompat.getColor(this, R.color.text_secondary),
        )
        appsChart.setValueTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.fraunces))
        hoursChart = findViewById(R.id.hoursChart)
        trendChart = findViewById(R.id.trendChart)

        val requested = intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        date = minOf(requested ?: today, today)
        worker.execute {
            val first = repo.getTrackingDays(resetHour).lastOrNull()?.first
            runOnUiThread { firstDay = first; updateButtons() }
        }
        show(date)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun updateButtons() {
        prevButton.isEnabled = firstDay?.let { date > it } ?: false
        nextButton.isEnabled = date < today
        prevButton.alpha = if (prevButton.isEnabled) 1f else 0.3f
        nextButton.alpha = if (nextButton.isEnabled) 1f else 0.3f
    }

    private fun show(day: LocalDate) {
        date = day
        findViewById<TextView>(R.id.dayTitle).text = when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> day.format(titleFormat)
        }
        val reset = "%02d:%02d".format(resetHour, resetMinute)
        findViewById<TextView>(R.id.dayRange).text =
            "${day.format(shortFormat)} $reset – ${day.plusDays(1).format(shortFormat)} $reset"
        updateButtons()

        worker.execute {
            // The current and previous day are still changing; pull them fresh from the system log.
            if (day >= today.minusDays(1)) appUsage.recordRecentDays()
            val data = load(day)
            runOnUiThread { if (!isDestroyed && date == day) render(data) }
        }
    }

    private fun load(day: LocalDate): DayData {
        val byLabel = linkedMapOf<String, Int>()
        // Two packages can share a label (e.g. a system component); show them as one.
        appUsage.getAppUsage(AnalyticsKeys.analyticsDateKey(day)).forEach { (pkg, s) ->
            appUsage.appLabel(pkg).let { byLabel[it] = (byLabel[it] ?: 0) + s }
        }
        val trendStart = day.minusDays(TREND_DAYS - 1L)
        val before = (1..AVERAGE_DAYS).map { repo.getTrackingDayTotal(day.minusDays(it.toLong()), resetHour) }
            .filter { it > 0 }
        return DayData(
            total = repo.getTrackingDayTotal(day, resetHour),
            hours = repo.getTrackingDayHours(day, resetHour),
            apps = byLabel.toList().sortedByDescending { it.second },
            trendStart = trendStart,
            trend = (0 until TREND_DAYS).map { repo.getTrackingDayTotal(trendStart.plusDays(it.toLong()), resetHour) },
            average = before.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size },
        )
    }

    private fun render(d: DayData) {
        findViewById<TextView>(R.id.dayTotal).text = formatDuration(d.total)
        findViewById<TextView>(R.id.dayCompare).text = when {
            d.total == 0 -> "Nothing recorded on this day"
            d.average == null -> "No earlier days to compare with yet"
            abs(d.total - d.average) < 60 -> "Same as your ${AVERAGE_DAYS}-day average"
            d.total < d.average -> "${formatDuration(d.average - d.total)} less than your ${AVERAGE_DAYS}-day average"
            else -> "${formatDuration(d.total - d.average)} more than your ${AVERAGE_DAYS}-day average"
        }
        renderApps(d.apps)
        renderHours(d.hours, d.total)

        trendChart.setData(d.trend, (0 until TREND_DAYS).map { d.trendStart.plusDays(it.toLong()).format(shortFormat) },
            TREND_DAYS - 1, ::formatDuration)
        trendChart.onPointTap = { i ->
            val target = d.trendStart.plusDays(i.toLong())
            if (target != date && target <= today) show(target)
        }
    }

    private fun renderApps(apps: List<Pair<String, Int>>) {
        val list = findViewById<LinearLayout>(R.id.appsList)
        val message = findViewById<TextView>(R.id.appsMessage)
        list.removeAllViews()
        if (apps.isEmpty()) {
            appsChart.visibility = View.GONE
            message.visibility = View.VISIBLE
            message.text = if (appUsage.hasPermission()) "No app use recorded on this day."
            else "Allow usage access (Analytics › Apps today) to see which apps your time went to."
            return
        }
        appsChart.visibility = View.VISIBLE
        message.visibility = View.GONE
        ChartStyle.showApps(this, appsChart, list, apps)
    }

    private fun renderHours(hours: Map<Int, Int>, total: Int) {
        val order = TrackingDay.hourOrder(resetHour)
        hoursChart.setData(order.map { hours[it] ?: 0 }, order.map { "%02d".format(it) }, 3, ::formatDuration)
        val insight = findViewById<TextView>(R.id.hoursInsight)
        val peak = hours.maxByOrNull { it.value }
        if (total == 0 || peak == null) {
            insight.text = "No screen time in any hour of this day."
            return
        }
        val active = hours.count { it.value >= 60 }
        insight.text = "Busiest hour: %02d:00–%02d:00 with ${formatDuration(peak.value)}. ".format(peak.key, (peak.key + 1) % 24) +
            "Screen on in $active of 24 hours."
    }

    private fun formatDuration(seconds: Int): String = ChartStyle.formatDuration(seconds)
}
