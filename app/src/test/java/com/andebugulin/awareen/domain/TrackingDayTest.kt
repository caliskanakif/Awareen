package com.andebugulin.awareen.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A tracking day runs from the reset time to the reset time. Screen time, app
 * time and the sync export all count by it, so it must match the counter.
 */
class TrackingDayTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(berlin).toInstant().toEpochMilli()

    @Test
    fun `after midnight but before the reset it is still the previous day`() {
        assertEquals(LocalDate.of(2026, 11, 15), TrackingDay.dateOf(at(2026, 11, 16, 1, 30), 5, 0, berlin))
        assertEquals(LocalDate.of(2026, 11, 16), TrackingDay.dateOf(at(2026, 11, 16, 5, 0), 5, 0, berlin))
    }

    @Test
    fun `with a midnight reset the tracking day is the calendar day`() {
        assertEquals(LocalDate.of(2026, 11, 16), TrackingDay.dateOf(at(2026, 11, 16, 0, 0), 0, 0, berlin))
        assertEquals(mapOf(0 to 10, 23 to 20), TrackingDay.hours(0, mapOf(0 to 10, 23 to 20), mapOf(1 to 99)))
    }

    @Test
    fun `the day runs from the reset time to the next reset time`() {
        val day = LocalDate.of(2026, 11, 15)
        assertEquals(at(2026, 11, 15, 5, 0), TrackingDay.startMillis(day, 5, 0, berlin))
        assertEquals(at(2026, 11, 16, 5, 0), TrackingDay.endMillis(day, 5, 0, berlin))
    }

    @Test
    fun `hours before the reset come from the next morning, not from the own date`() {
        // Own date: 01:00 belongs to the previous day, 21:00 to this one.
        // Next date: 00:00 and 04:00 still belong to this day, 08:00 to the next.
        val hours = TrackingDay.hours(5, mapOf(1 to 300, 21 to 600), mapOf(0 to 120, 4 to 60, 8 to 900))
        assertEquals(mapOf(0 to 120, 4 to 60, 21 to 600), hours)
    }

    @Test
    fun `hour order starts at the reset hour and wraps around`() {
        assertEquals(listOf(5, 6, 7), TrackingDay.hourOrder(5).take(3))
        assertEquals(listOf(3, 4), TrackingDay.hourOrder(5).takeLast(2))
        assertEquals(24, TrackingDay.hourOrder(5).size)
    }
}
