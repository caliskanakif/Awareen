package com.andebugulin.awareen.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTimeSyncExporterTest {

    @Test
    fun `the day total is the sum of the hourly buckets, not the reset-bound counter`() {
        // Reset at 05:00: at 00:10 the counter still holds yesterday's 2 h, then is zeroed.
        assertEquals(837, ScreenTimeSyncExporter.dayTotal(137, mapOf(0 to 700, 14 to 108, 18 to 29)))
    }

    @Test
    fun `days without hourly data keep the counter`() {
        assertEquals(1200, ScreenTimeSyncExporter.dayTotal(1200, emptyMap()))
    }
}
