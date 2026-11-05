package com.youloft.watcher.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.res.use
import androidx.recyclerview.widget.RecyclerView
import com.youloft.watcher.R as AppR

/**
 * Rebuilt from the 1.5.6 decompiled classes of the same names. Method bodies are ported only where
 * the decompiled signature was unambiguous; the obfuscated ones (d/R/S/T/V) are left to the screen
 * cards that actually call them rather than invented here.
 */

class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    var maxHeight: Int = 0
        set(value) {
            field = value
            requestLayout()
        }

    init {
        attrs?.let { a ->
            context.obtainStyledAttributes(a, intArrayOf(AppR.attr.maxHeight)).use {
                maxHeight = it.getDimensionPixelSize(0, 0)
            }
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val spec = if (maxHeight > 0) {
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        } else {
            heightSpec
        }
        super.onMeasure(widthSpec, spec)
    }
}

/** Outlined text, with an optional horizontal or vertical gradient shader. */
open class StrokeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var strokeWidth = dp(0.5f)
    private var strokeColor = -1
    private var gradientColors: IntArray? = null
    private var gradientPositions: FloatArray? = null
    private var vertical = false

    init {
        attrs?.let { a ->
            context.obtainStyledAttributes(a, STROKE_ATTRS).use { t ->
                strokeWidth = t.getDimension(0, strokeWidth)
                strokeColor = t.getColor(1, strokeColor)
                t.getString(2)?.let { gradientColors = it.split(",").map { c -> c.trim().toColorInt() }.toIntArray() }
                t.getString(3)?.let {
                    gradientPositions = it.split(",").map { p -> p.trim().toFloat() }.toFloatArray()
                }
                vertical = t.getInt(4, 0) != 0
            }
        }
        strokePaint.strokeWidth = strokeWidth
        strokePaint.color = strokeColor
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val colors = gradientColors ?: return
        val (x1, y1) = if (vertical) 0f to h.toFloat() else w.toFloat() to 0f
        paint.shader = LinearGradient(0f, 0f, x1, y1, colors, gradientPositions, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val colors = gradientColors
        if (colors != null && width > 0 && paint.shader == null) {
            val (x1, y1) = if (vertical) 0f to height.toFloat() else width.toFloat() to 0f
            paint.shader = LinearGradient(0f, 0f, x1, y1, colors, gradientPositions, Shader.TileMode.CLAMP)
        }
        super.onDraw(canvas)
        val textLayout = layout ?: return
        val path = Path()
        paint.getTextPath(
            text.toString(), 0, text.length,
            textLayout.getLineLeft(0) + paddingLeft,
            textLayout.getLineBaseline(0).toFloat() + paddingTop,
            path,
        )
        canvas.drawPath(path, strokePaint)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    protected open fun String.toColorInt(): Int = android.graphics.Color.parseColor(this)

    private companion object {
        val STROKE_ATTRS = intArrayOf(
            AppR.attr.yl_strokeWidth,
            AppR.attr.yl_strokeColor,
            AppR.attr.yl_textGradientColor,
            AppR.attr.yl_textGradientPosition,
            AppR.attr.yl_textGradientOrientation,
        )
    }
}

class RollingTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : StrokeTextView(context, attrs, defStyleAttr)

/** Text with an underline drawn under the marked part of the string. */
class BottomLineTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lineHeightPx = 2f * resources.displayMetrics.density
    var mark: String? = null

    init {
        attrs?.let { a ->
            context.obtainStyledAttributes(a, LINE_ATTRS).use { t ->
                linePaint.color = t.getColor(0, 0xFF333333.toInt())
                lineHeightPx = t.getDimension(1, lineHeightPx)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val target = mark
        val textLayout = layout
        if (target.isNullOrEmpty() || textLayout == null) return
        val text = text.toString()
        val start = text.indexOf(target)
        if (start < 0) return
        val line = textLayout.getLineForOffset(start)
        val left = textLayout.getLineLeft(line) + textLayout.getPrimaryHorizontal(start) + paddingLeft
        val right = left + paint.measureText(target)
        val baseline = textLayout.getLineBaseline(line) + paddingTop
        canvas.drawRect(left, baseline + lineHeightPx, right, baseline + lineHeightPx * 2, linePaint)
    }

    private companion object {
        val LINE_ATTRS = intArrayOf(AppR.attr.bottomLineColor, AppR.attr.bottomLineHeight)
    }
}

/** Row of page dots; the original binds itself to a ViewPager2 through setPageCount/attach. */
class DotIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    fun setPageCount(count: Int, selected: Int = 0) {
        removeAllViews()
        val dot = (6f * resources.displayMetrics.density).toInt()
        repeat(count) { index ->
            val view = View(context)
            val size = if (index == selected) dot * 3 else dot
            addView(view, LayoutParams(size, dot).apply {
                leftMargin = dot / 2
                rightMargin = dot / 2
            })
            view.alpha = if (index == selected) 1f else 0.35f
        }
    }
}

/** Ring showing app-screen-usage ratio, driven by `update(ratio)`. */
class ScreenUsageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var ratio = 0f

    fun update(ratio: Float) {
        this.ratio = ratio.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val bounds = (size - paint.strokeWidth) / 2f
        canvas.drawCircle(width / 2f, height / 2f, bounds, paint)
        if (ratio > 0f) {
            canvas.drawArc(
                width / 2f - bounds, height / 2f - bounds,
                width / 2f + bounds, height / 2f + bounds,
                -90f, 360f * ratio, false, paint,
            )
        }
    }
}
