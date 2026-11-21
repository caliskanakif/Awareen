package com.andebugulin.awareen.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleMathTest {

    private val minute = 60_000L
    private val touch = 1_000_000L

    @Test
    fun `counts while the last touch is within the timeout`() {
        assertTrue(IdleMath.counts(touch + 4 * minute, touch, 5, mediaPlaying = false))
    }

    @Test
    fun `stops counting once the timeout has passed without a touch`() {
        assertFalse(IdleMath.counts(touch + 5 * minute, touch, 5, mediaPlaying = false))
        assertFalse(IdleMath.counts(touch + 60 * minute, touch, 5, mediaPlaying = false))
    }

    @Test
    fun `playing media keeps counting without touches`() {
        assertTrue(IdleMath.counts(touch + 60 * minute, touch, 5, mediaPlaying = true))
    }

    @Test
    fun `timeout off always counts`() {
        assertTrue(IdleMath.counts(touch + 600 * minute, touch, IdleMath.OFF, mediaPlaying = false))
    }
}
