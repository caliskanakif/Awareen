package com.andebugulin.awareen.domain

import com.andebugulin.awareen.domain.AppUsageMath.Event
import com.andebugulin.awareen.domain.AppUsageMath.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUsageMathTest {

    private fun fg(s: Long, pkg: String) = Event(s * 1000, pkg, Type.FOREGROUND)
    private fun bg(s: Long, pkg: String) = Event(s * 1000, pkg, Type.BACKGROUND)
    private fun off(s: Long) = Event(s * 1000, "android", Type.SCREEN_OFF)

    private fun run(vararg events: Event, start: Long = 0, end: Long = 10_000) =
        AppUsageMath.foregroundSeconds(events.toList(), start * 1000, end * 1000)

    @Test
    fun `a foreground then background pair counts the time between`() {
        assertEquals(mapOf("a" to 60), run(fg(100, "a"), bg(160, "a")))
    }

    @Test
    fun `a new app coming forward ends the previous one`() {
        assertEquals(mapOf("a" to 10, "b" to 20), run(fg(0, "a"), fg(10, "b"), bg(30, "b")))
    }

    @Test
    fun `screen off ends the current app`() {
        assertEquals(mapOf("a" to 5), run(fg(0, "a"), off(5), bg(50, "a")))
    }

    @Test
    fun `an app still open at the end is counted up to the end`() {
        assertEquals(mapOf("a" to 40), run(fg(60, "a"), end = 100))
    }

    @Test
    fun `an app open across the start is counted from the start`() {
        assertEquals(mapOf("a" to 30), run(fg(50, "a"), bg(130, "a"), start = 100))
    }

    @Test
    fun `sessions entirely before the start are ignored`() {
        assertEquals(mapOf("b" to 10), run(fg(0, "a"), bg(50, "a"), fg(100, "b"), bg(110, "b"), start = 60))
    }

    @Test
    fun `a stray background event of another app does not close the current one`() {
        assertEquals(mapOf("a" to 30), run(fg(0, "a"), bg(10, "b"), bg(30, "a")))
    }

    @Test
    fun `repeated foreground events of the same app keep one session`() {
        assertEquals(mapOf("a" to 30), run(fg(0, "a"), fg(10, "a"), bg(30, "a")))
    }

    @Test
    fun `sessions shorter than a second are dropped`() {
        val result = AppUsageMath.foregroundSeconds(
            listOf(Event(0, "a", Type.FOREGROUND), Event(500, "a", Type.BACKGROUND)), 0, 10_000
        )
        assertTrue(result.isEmpty())
    }
}
