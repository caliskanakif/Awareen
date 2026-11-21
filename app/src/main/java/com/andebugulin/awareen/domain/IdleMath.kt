package com.andebugulin.awareen.domain

/**
 * Whether a second of screen-on time counts, given when the screen was last
 * touched. A screen left on untouched (a phone lying next to you, an
 * e-reader page you stopped reading) stops counting after the idle timeout,
 * unless media is playing: watching a video needs no taps.
 *
 * Pure, like [ResetMath]: the clock, the last touch and the media state are
 * parameters.
 */
object IdleMath {

    /** 0 turns the idle pause off. */
    const val OFF = 0

    fun counts(nowMillis: Long, lastTouchMillis: Long, timeoutMinutes: Int, mediaPlaying: Boolean): Boolean {
        if (timeoutMinutes <= OFF || mediaPlaying) return true
        return nowMillis - lastTouchMillis < timeoutMinutes * 60_000L
    }
}
