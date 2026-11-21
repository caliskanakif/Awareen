package com.andebugulin.awareen.overlay

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The "Timer Pill: Glass + Border on Top" look as an overlay background.
 *
 * Layer order follows the reference page, except that its outer drop shadow
 * (0 12px 34px rgba(0,0,0,.5)) is left out at the user's request:
 *   the border's glow (0 0 14px rgba(border,.25)), outside the pill only →
 *   glass body (rgba(255,255,255,.05)) → inset glow (inset 0 0 22px
 *   rgba(255,255,255,.06)) → top highlight (inset 0 1px 0 rgba(255,255,255,.35))
 *   → glossy sheen (linear-gradient(165deg, .22, 0 40%, 0 72%, .08)) →
 *   solid border laid on top of the glass.
 *
 * The page's sizes are CSS px on a 188 px tall pill, so everything is scaled
 * by the real pill height. What Android can't do from an overlay is read the
 * pixels behind it, so the page's backdrop refraction / 2 px blur is left out.
 *
 * [room] is transparent space kept around the pill for the shadow and glow;
 * the owning view pads its content by the same amount.
 */
class GlassPillDrawable(private val density: Float) : Drawable() {

    private companion object {
        const val REF_HEIGHT = 187.8f            // the page's pill: 460 px wide, aspect 2.45:1
        const val CSS_BLUR_TO_RADIUS = 0.866f    // CSS blur(px) = 2σ; Skia radius ≈ (σ − .5) / .57735
    }

    var room: Float = 10f * density
    var rounded: Boolean = true
    var bodyColor: Int = Color.argb(13, 255, 255, 255)   // rgba(255,255,255,.05)
    var borderColor: Int = Color.WHITE

    private val pill = RectF()
    private val tmp = RectF()
    private val path = Path()
    private val shifted = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun draw(canvas: Canvas) {
        val b = bounds
        pill.set(b.left + room, b.top + room, b.right - room, b.bottom - room)
        val h = pill.height()
        if (h <= 0f || pill.width() <= 0f) return
        val k = h / REF_HEIGHT
        val r = if (rounded) min(h, pill.width()) / 2f else 0f

        path.reset()
        path.addRoundRect(pill, r, r, Path.Direction.CW)

        // ---- outside the pill: the border's glow (the page's drop shadow is left out on purpose)
        canvas.save()
        canvas.clipOutPath(path)
        fill.shader = null
        fill.style = Paint.Style.FILL
        blurred(fill, 14f * k)
        fill.color = withAlpha(borderColor, 0.25f)
        canvas.drawRoundRect(pill, r, r, fill)
        fill.maskFilter = null
        canvas.restore()

        // ---- inside the pill
        canvas.save()
        canvas.clipPath(path)

        // glass body
        fill.color = bodyColor
        canvas.drawRect(pill, fill)

        // inset 0 0 22px rgba(255,255,255,.06): a blurred band just outside the edge, clipped to the inside
        val band = 44f * k
        stroke.strokeWidth = band
        stroke.color = Color.argb(15, 255, 255, 255)
        blurred(stroke, 22f * k)
        tmp.set(pill); tmp.inset(-band / 2f, -band / 2f)
        canvas.drawRoundRect(tmp, r + band / 2f, r + band / 2f, stroke)
        stroke.maskFilter = null

        // inset 0 1px 0 rgba(255,255,255,.35): the sliver the pill loses when shifted down a pixel
        val line = max(1f, 0.75f * density)
        shifted.reset()
        path.offset(0f, line, shifted)
        canvas.save()
        canvas.clipOutPath(shifted)
        fill.color = Color.argb(89, 255, 255, 255)
        canvas.drawRect(pill, fill)
        canvas.restore()

        // ::before glossy sheen, linear-gradient(165deg, ...) — CSS gradient line through the centre
        val dx = 0.25881905f; val dy = 0.96592583f          // (sin 165°, −cos 165°), y pointing down
        val half = (abs(pill.width() * dx) + abs(h * dy)) / 2f
        val cx = pill.centerX(); val cy = pill.centerY()
        fill.shader = LinearGradient(
            cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
            intArrayOf(Color.argb(56, 255, 255, 255), 0x00FFFFFF, 0x00FFFFFF, Color.argb(20, 255, 255, 255)),
            floatArrayOf(0f, 0.40f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(pill, fill)
        fill.shader = null
        canvas.restore()

        // ---- ::after the solid border, laid on top of the glass (4 px on the page's 188 px pill)
        val bw = max(1.5f * density, 4f * k)
        stroke.strokeWidth = bw
        stroke.color = borderColor
        tmp.set(pill); tmp.inset(bw / 2f, bw / 2f)
        val br = max(0f, r - bw / 2f)
        canvas.drawRoundRect(tmp, br, br, stroke)
    }

    private fun blurred(p: Paint, cssBlur: Float) {
        val radius = cssBlur * CSS_BLUR_TO_RADIUS
        p.maskFilter = if (radius >= 0.5f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null
    }

    private fun withAlpha(c: Int, a: Float): Int =
        Color.argb((Color.alpha(c) * a).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
