package com.andebugulin.awareen.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A "day" as the user set it: from the reset time on a date to the reset time
 * on the next date (e.g. 05:00 to 05:00), named after the date it starts on.
 * Screen time, app time, statistics and the sync export all count by it, so
 * the numbers agree with the counter that resets at that time.
 *
 * Hourly buckets are stored per calendar date and clock hour, so a tracking
 * day is assembled from two dates: clock hours from the reset hour onwards
 * of its own date, earlier hours of the next date. The split is per hour; with
 * a reset at e.g. 05:30 the whole 05:00 bucket counts to the new day.
 *
 * Pure: no Android types and no current time, like [ResetMath].
 */
object TrackingDay {

    /** The tracking day [nowMillis] falls into. */
    fun dateOf(nowMillis: Long, hour: Int, minute: Int, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(ResetMath.mostRecentReset(nowMillis, hour, minute, zone)).atZone(zone).toLocalDate()

    fun startMillis(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Long =
        ResetMath.resetOn(date, hour, minute, zone)

    fun endMillis(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Long =
        ResetMath.resetOn(date.plusDays(1), hour, minute, zone)

    /**
     * Clock hour → seconds for the tracking day, from the hourly buckets of its
     * own date ([sameDate]) and of the next date ([nextDate]).
     */
    fun hours(resetHour: Int, sameDate: Map<Int, Int>, nextDate: Map<Int, Int>): Map<Int, Int> {
        val split = resetHour.coerceIn(0, 23)
        val out = sortedMapOf<Int, Int>()
        sameDate.forEach { (h, s) -> if (h >= split && s > 0) out[h] = s }
        nextDate.forEach { (h, s) -> if (h < split && s > 0) out[h] = s }
        return out
    }

    /** Clock hours in the order the day runs, e.g. 5, 6 … 23, 0 … 4. */
    fun hourOrder(resetHour: Int): List<Int> {
        val split = resetHour.coerceIn(0, 23)
        return (split..23) + (0 until split)
    }
}
