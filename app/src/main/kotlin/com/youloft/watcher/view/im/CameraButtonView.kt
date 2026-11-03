package com.youloft.watcher.view.im

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.youloft.watcher.R

/**
 * Shutter button for the in-chat camera screen: an outer ring plus an inner disc that shrinks while
 * the picture is being taken. The decompiled original animates the inner disc with a TimerTask;
 * a value animator replaces it here because it stays on the Choreographer clock.
 */
class CameraButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.white)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        color = ContextCompat.getColor(context, R.color.white)
    }
    private var progress = 0f
    private var animator: android.animation.ValueAnimator? = null

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy)
        canvas.drawCircle(cx, cy, radius - ringPaint.strokeWidth, ringPaint)
        val inner = (radius - ringPaint.strokeWidth * 3f) * (1f - progress * 0.4f)
        canvas.drawCircle(cx, cy, inner, fillPaint)
    }

    fun startCaptureProgress(durationMillis: Long = 1000L) {
        stopCaptureProgress()
        animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMillis
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopCaptureProgress() {
        animator?.cancel()
        animator = null
        progress = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopCaptureProgress()
        super.onDetachedFromWindow()
    }
}
