package com.youloft.watcher.view

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable

/**
 * Recovered from `com.youloft.watcher.utils.t`: a wrapper that paints a full-bleed [inner] layer and
 * then CLEARs one rectangle out of it, which is how the scan screen gets a dimmed view with a bright
 * window over the finder frame.
 *
 * The rectangle is captured once at construction from the frame's position, so the caller has to build
 * this after a layout pass — the original did it inside `frameLayout.post { ... }` for exactly that
 * reason.
 */
class ScanMaskDrawable(
    private val inner: Drawable,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
) : Drawable() {

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val hole = Path().apply { addRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), Path.Direction.CW) }

    override fun draw(canvas: Canvas) {
        inner.setBounds(bounds)
        if (hole.isEmpty) {
            inner.draw(canvas)
            return
        }
        val layer = canvas.saveLayer(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), clearPaint, 31)
        inner.draw(canvas)
        clearPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        canvas.drawPath(hole, clearPaint)
        clearPaint.xfermode = null
        canvas.restoreToCount(layer)
    }

    override fun setAlpha(alpha: Int) {
        inner.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        inner.colorFilter = colorFilter
    }

    @Deprecated("Required by Drawable; the wrapped layer decides the real blending behaviour.")
    override fun getOpacity(): Int = inner.opacity
}
