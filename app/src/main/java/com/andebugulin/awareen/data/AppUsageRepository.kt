package com.andebugulin.awareen.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import com.andebugulin.awareen.domain.AnalyticsKeys
import com.andebugulin.awareen.domain.AppUsageMath
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * Per-app foreground time, read from the system's usage log and kept in
 * Awareen's own prefs so the history outlives the system's few-day retention.
 *
 * Needs the special "Usage access" grant ([hasPermission]); without it every
 * read is empty and [recordRecentDays] is a no-op. Nothing leaves the device.
 */
class AppUsageRepository(
    context: Context,
    private val prefs: SharedPreferences,
) {
    private val appContext = context.applicationContext

    constructor(context: Context) : this(
        context,
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun hasPermission(): Boolean {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // =========================================================================
    // WRITES
    // =========================================================================

    /**
     * Recompute today and yesterday from the system log and store them.
     * Yesterday is included so its last minutes before midnight are captured
     * even if the previous run happened earlier in the evening. Does I/O and
     * may take tens of milliseconds — call off the main thread.
     */
    fun recordRecentDays() {
        if (!hasPermission()) return
        val today = LocalDate.now()
        recordDay(today.minusDays(1))
        recordDay(today)
    }

    private fun recordDay(date: LocalDate) {
        val usage = computeDay(date)
        // An empty result means the system no longer has that day (or the
        // phone was unused) — never overwrite stored history with nothing.
        if (usage.isEmpty()) return
        val json = JSONObject()
        usage.forEach { (pkg, seconds) -> json.put(pkg, seconds) }
        prefs.edit()
            .putString(AnalyticsKeys.appUsageKey(AnalyticsKeys.analyticsDateKey(date)), json.toString())
            .apply()
    }

    private fun computeDay(date: LocalDate): Map<String, Int> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            System.currentTimeMillis()
        )
        if (end <= start) return emptyMap()

        val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = mutableListOf<AppUsageMath.Event>()
        try {
            // Look back an hour so an app already open at midnight is known.
            val raw = usm.queryEvents(start - LOOKBACK_MS, end)
            val e = UsageEvents.Event()
            while (raw.hasNextEvent()) {
                raw.getNextEvent(e)
                // MOVE_TO_* were renamed ACTIVITY_RESUMED/PAUSED in API 29
                // with the same values; the old names work on every API level.
                @Suppress("DEPRECATION")
                val type = when (e.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> AppUsageMath.Type.FOREGROUND
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> AppUsageMath.Type.BACKGROUND
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> AppUsageMath.Type.SCREEN_OFF
                    else -> null
                } ?: continue
                events.add(AppUsageMath.Event(e.timeStamp, e.packageName ?: "", type))
            }
        } catch (ex: Exception) {
            Log.e(TAG, "queryEvents failed: ${ex.message}", ex)
            return emptyMap()
        }

        val ignored = ignoredPackages()
        return AppUsageMath.foregroundSeconds(events, start, end)
            .filterKeys { it.isNotEmpty() && it !in ignored }
    }

    /**
     * The home screen and system UI aren't "apps you used". Only the default
     * launcher is resolved: listing every HOME handler would also catch
     * Settings, which ships a FallbackHome activity for early boot.
     */
    private fun ignoredPackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcher = appContext.packageManager
            .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        return setOfNotNull(launcher, "com.android.systemui")
    }

    // =========================================================================
    // READS
    // =========================================================================

    /** Package → seconds for [dateKey] (an `analytics_…` key), largest first. */
    fun getAppUsage(dateKey: String): List<Pair<String, Int>> {
        val raw = prefs.getString(AnalyticsKeys.appUsageKey(dateKey), null) ?: return emptyList()
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence()
                .map { it to json.optInt(it, 0) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Raw stored JSON for export; null when the day has no app data. */
    fun getAppUsageJson(dateKey: String): JSONObject? =
        prefs.getString(AnalyticsKeys.appUsageKey(dateKey), null)?.let {
            try { JSONObject(it) } catch (e: Exception) { null }
        }

    fun importAppUsage(dateKey: String, json: JSONObject) {
        prefs.edit().putString(AnalyticsKeys.appUsageKey(dateKey), json.toString()).apply()
    }

    /** Human-readable name, or the package name if the app is gone or hidden. */
    fun appLabel(packageName: String): String = try {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    companion object {
        private const val TAG = "AppUsageRepository"
        private const val LOOKBACK_MS = 60 * 60 * 1000L
    }
}
