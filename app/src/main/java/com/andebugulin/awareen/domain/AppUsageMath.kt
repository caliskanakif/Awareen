package com.andebugulin.awareen.domain

/**
 * Turns the system's raw usage event stream into foreground seconds per app.
 *
 * Pure: no Android types, so it can be tested on the JVM. The data layer maps
 * `UsageEvents.Event` into [Event] and hands the list over in time order.
 */
object AppUsageMath {

    /**
     * PAUSE / RESUME are Awareen's own idle pauses (screen on, untouched, no
     * media): the app in front stays in front, but its time doesn't count,
     * the same as for the screen-time total.
     */
    enum class Type { FOREGROUND, BACKGROUND, SCREEN_OFF, PAUSE, RESUME }

    data class Event(val timestampMs: Long, val packageName: String, val type: Type)

    /**
     * Foreground time per package within [startMs, endMs), in whole seconds.
     *
     * Only one app is in the foreground at a time: an app coming forward ends
     * whatever was running before it, its own background event ends it, and so
     * does the screen turning off. An app still open at [endMs] is counted up to
     * [endMs]. Packages with less than one second are left out.
     */
    fun foregroundSeconds(events: List<Event>, startMs: Long, endMs: Long): Map<String, Int> {
        val totalsMs = mutableMapOf<String, Long>()
        var current: String? = null
        var currentSince = 0L
        var paused = false

        /** Adds the current app's time up to [atMs] (none while paused). */
        fun count(atMs: Long) {
            val pkg = current ?: return
            val from = currentSince.coerceAtLeast(startMs)
            val to = atMs.coerceAtMost(endMs)
            if (!paused && to > from) totalsMs[pkg] = (totalsMs[pkg] ?: 0L) + (to - from)
        }

        fun close(atMs: Long) {
            count(atMs)
            current = null
        }

        for (e in events) {
            when (e.type) {
                Type.FOREGROUND -> if (e.packageName != current) {
                    close(e.timestampMs)
                    current = e.packageName
                    currentSince = e.timestampMs
                }
                Type.BACKGROUND -> if (e.packageName == current) close(e.timestampMs)
                Type.SCREEN_OFF -> close(e.timestampMs)
                Type.PAUSE -> if (!paused) {
                    count(e.timestampMs)
                    paused = true
                }
                Type.RESUME -> if (paused) {
                    paused = false
                    currentSince = e.timestampMs   // the app still in front counts again from here
                }
            }
        }
        close(endMs)

        return totalsMs
            .mapValues { (it.value / 1000).toInt() }
            .filterValues { it > 0 }
    }
}
