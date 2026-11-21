package com.andebugulin.awareen.data

import android.content.SharedPreferences
import org.json.JSONArray

/**
 * When counting was paused for being idle (screen on, untouched, no media),
 * so per-app time can leave out the same minutes as the screen-time total.
 * The service writes it; [AppUsageRepository] reads it.
 *
 * Kept as `[[start, end], …]` in epoch milliseconds, pruned to the days the
 * system's usage log still covers, plus the start of a pause still running.
 */
class IdlePauseLog(private val prefs: SharedPreferences) {

    /** Counting just paused (no-op while a pause is already open). */
    fun begin(nowMillis: Long) {
        if (!prefs.contains(KEY_OPEN)) prefs.edit().putLong(KEY_OPEN, nowMillis).apply()
    }

    /** Counting resumed or the screen went off/locked (no-op without an open pause). */
    fun end(nowMillis: Long) {
        val start = prefs.getLong(KEY_OPEN, -1L)
        if (start < 0) return
        val kept = closed().filter { it.second > nowMillis - KEEP_MS }
        val out = JSONArray()
        (kept + (start to nowMillis)).forEach { (s, e) -> out.put(JSONArray().put(s).put(e)) }
        prefs.edit().putString(KEY_CLOSED, out.toString()).remove(KEY_OPEN).apply()
    }

    /** Pauses overlapping [fromMillis, toMillis), the running one up to [toMillis]. */
    fun between(fromMillis: Long, toMillis: Long): List<Pair<Long, Long>> {
        val open = prefs.getLong(KEY_OPEN, -1L).takeIf { it >= 0 }?.let { it to toMillis }
        return (closed() + listOfNotNull(open)).filter { (s, e) -> s < toMillis && e > fromMillis }
    }

    private fun closed(): List<Pair<Long, Long>> = try {
        val arr = JSONArray(prefs.getString(KEY_CLOSED, "[]"))
        (0 until arr.length()).map { i -> arr.getJSONArray(i).let { it.getLong(0) to it.getLong(1) } }
    } catch (e: Exception) {
        emptyList()
    }

    private companion object {
        const val KEY_OPEN = "idle_pause_open"
        const val KEY_CLOSED = "idle_pauses"
        const val KEEP_MS = 12L * 24 * 60 * 60 * 1000
    }
}
