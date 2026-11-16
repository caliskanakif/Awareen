package com.andebugulin.awareen.data

import android.content.Context
import android.content.SharedPreferences
import com.andebugulin.awareen.domain.AnalyticsKeys
import com.andebugulin.awareen.domain.TrackingDay
import java.time.LocalDate
import java.util.Calendar

/**
 * Single source of truth for screen-time persistence: today's running total,
 * reset bookkeeping, and analytics writes/reads.
 *
 * Pure I/O. Holds no business logic about *when* to reset — that decision
 * belongs to the caller (the service's tick loop and the activity's defensive
 * check both call [markReset] when they decide a reset is due).
 */
class ScreenTimeRepository(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
    )

    // =========================================================================
    // TODAY'S SCREEN TIME
    // =========================================================================

    fun getTodayScreenTime(): Int = prefs.getInt(todayDateKey(), 0)

    fun saveTodayScreenTime(seconds: Int) {
        val todayKey = todayDateKey()
        prefs.edit()
            .putInt(todayKey, seconds)
            .putString(KEY_LAST_DATE, todayKey)
            .putLong(KEY_LAST_SAVE, System.currentTimeMillis())
            .apply()
    }

    // =========================================================================
    // RESET BOOKKEEPING
    // =========================================================================

    fun getLastResetTimestamp(): Long = prefs.getLong(KEY_LAST_RESET, 0L)

    /**
     * Zero today's counter and stamp the reset moment. Used by both the
     * service's main reset path and the activity's defensive check — keeping
     * the write here ensures both paths produce identical state.
     */
    fun markReset() {
        val todayKey = todayDateKey()
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_LAST_DATE, todayKey)
            .putInt(todayKey, 0)
            .putLong(KEY_LAST_SAVE, now)
            .putLong(KEY_LAST_RESET, now)
            .apply()
    }

    // =========================================================================
    // ANALYTICS — WRITES
    // =========================================================================

    /**
     * Record one tick (one second of screen-on time) into the analytics
     * tables: today's daily total, today's hourly bucket, and the date index.
     */
    fun recordAnalyticsTick(seconds: Int) {
        val cal = Calendar.getInstance()
        val dateKey = AnalyticsKeys.analyticsDateKey(LocalDate.now())
        val hourKey = AnalyticsKeys.hourKey(dateKey, cal.get(Calendar.HOUR_OF_DAY))
        val currentHour = prefs.getInt(hourKey, 0)
        // getStringSet returns a backing set whose mutation is undefined —
        // copy before edit.
        val dates = prefs.getStringSet(KEY_ANALYTICS_DATES, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        dates.add(dateKey)
        prefs.edit()
            .putInt(dateKey, seconds)
            .putInt(hourKey, currentHour + 1)
            .putStringSet(KEY_ANALYTICS_DATES, dates)
            .apply()
    }

    // =========================================================================
    // ANALYTICS — READS (used by AnalyticsActivity)
    // =========================================================================

    fun getAnalyticsDateKeys(): Set<String> =
        prefs.getStringSet(KEY_ANALYTICS_DATES, emptySet())?.toSet() ?: emptySet()

    fun getDailyScreenTime(dateKey: String): Int = prefs.getInt(dateKey, 0)

    fun getHourlyBreakdown(dateKey: String): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        for (h in 0..23) {
            val v = prefs.getInt(AnalyticsKeys.hourKey(dateKey, h), 0)
            if (v > 0) result[h] = v
        }
        return result
    }

    // =========================================================================
    // TRACKING DAYS — reset time to reset time (see TrackingDay)
    // =========================================================================

    /** Clock hour → seconds of the tracking day starting on [date] at [resetHour]. */
    fun getTrackingDayHours(date: LocalDate, resetHour: Int): Map<Int, Int> = TrackingDay.hours(
        resetHour,
        getHourlyBreakdown(AnalyticsKeys.analyticsDateKey(date)),
        getHourlyBreakdown(AnalyticsKeys.analyticsDateKey(date.plusDays(1))),
    )

    /**
     * Screen time of the tracking day starting on [date]. The running counter
     * can't be used: after midnight it writes the previous day's total under
     * the new date until the reset. Only days imported without an hourly
     * breakdown fall back to it.
     */
    fun getTrackingDayTotal(date: LocalDate, resetHour: Int): Int {
        val hours = getTrackingDayHours(date, resetHour)
        return if (hours.isNotEmpty()) hours.values.sum()
        else getDailyScreenTime(AnalyticsKeys.analyticsDateKey(date))
    }

    /**
     * Every date a tracking day could start on: each stored date, and the one
     * before it (its early hours belong to the previous tracking day).
     */
    fun getTrackingDayCandidates(): Set<LocalDate> =
        getAnalyticsDateKeys().mapNotNull(AnalyticsKeys::parseAnalyticsDateKey)
            .flatMap { listOf(it, it.minusDays(1)) }
            .toSortedSet()

    /** Tracking days with any screen time, newest first. */
    fun getTrackingDays(resetHour: Int): List<Pair<LocalDate, Int>> =
        getTrackingDayCandidates()
            .map { it to getTrackingDayTotal(it, resetHour) }
            .filter { it.second > 0 }
            .sortedByDescending { it.first }

    /**
     * Bulk write for analytics import. The caller decides whether to import
     * (e.g. only if greater than existing) — this just writes.
     */
    fun importDay(dateKey: String, screenTimeSeconds: Int, hourlyBreakdown: Map<Int, Int>) {
        val dates = prefs.getStringSet(KEY_ANALYTICS_DATES, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        dates.add(dateKey)
        val editor = prefs.edit()
            .putInt(dateKey, screenTimeSeconds)
            .putStringSet(KEY_ANALYTICS_DATES, dates)
        hourlyBreakdown.forEach { (hour, value) ->
            editor.putInt(AnalyticsKeys.hourKey(dateKey, hour), value)
        }
        editor.apply()
    }

    // =========================================================================
    // KEY HELPERS
    // =========================================================================

    private fun todayDateKey(): String = AnalyticsKeys.dailyScreenTimeKey(LocalDate.now())

    companion object {
        private const val KEY_LAST_DATE = "last_date_key"
        private const val KEY_LAST_SAVE = "last_save_timestamp"
        private const val KEY_LAST_RESET = "last_actual_reset_timestamp"
        private const val KEY_ANALYTICS_DATES = "analytics_dates"
    }
}
