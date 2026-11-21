package com.andebugulin.awareen.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.andebugulin.awareen.domain.IdleMath

/**
 * Notices when the screen was last touched, anywhere, without an
 * accessibility service: a 1×1 px invisible overlay window asks for
 * FLAG_WATCH_OUTSIDE_TOUCH, so Android tells it about every touch-down
 * outside it (without coordinates or any content). Awareen already holds the
 * overlay permission for its timer.
 *
 * Together with "is media playing" this decides whether a second counts
 * (see [IdleMath]).
 */
class IdleWatcher(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var view: View? = null

    @Volatile
    private var lastTouchMillis = System.currentTimeMillis()

    /** Screen on, unlock and touches all count as the user being there. */
    fun touched() {
        lastTouchMillis = System.currentTimeMillis()
    }

    fun counts(timeoutMinutes: Int): Boolean = IdleMath.counts(
        System.currentTimeMillis(), lastTouchMillis, timeoutMinutes, audioManager.isMusicActive
    )

    @SuppressLint("ClickableViewAccessibility")
    fun start() {
        if (view != null || !Settings.canDrawOverlays(context)) return
        val params = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "AwareenIdleWatcher"
        }
        val v = View(context).apply {
            // ACTION_OUTSIDE for touches elsewhere; the window itself is one pixel in the corner.
            setOnTouchListener { _, _ -> touched(); false }
        }
        try {
            windowManager.addView(v, params)
            view = v
        } catch (e: Exception) {
            Log.e(TAG, "Could not add idle watcher window: ${e.message}", e)
        }
    }

    fun stop() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    companion object {
        private const val TAG = "IdleWatcher"
    }
}
