package com.mc.fastkit.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.use
import com.youloft.watcher.res166.R as BankR

/**
 * Rebuilt from the 1.5.6 decompiled `com.mc.fastkit.view.shape` classes.
 *
 * The shipping APK keeps these as flat top-level `<attr name="mc_*"/>` entries (declare-styleable
 * is compile-time only), so the views read them through their own attribute array instead of
 * `R.styleable.ShapedXxx`. The enum values below are taken from the bank's attrs.xml, not guessed.
 */
object ShapeStyle {
    const val RENDER_BACKGROUND = 0
    const val RENDER_FOREGROUND = 1

    private val ATTRS = intArrayOf(
        BankR.attr.mc_contentColor,
        BankR.attr.mc_radius,
        BankR.attr.mc_topLeftRadius,
        BankR.attr.mc_topRightRadius,
        BankR.attr.mc_bottomLeftRadius,
        BankR.attr.mc_bottomRightRadius,
        BankR.attr.mc_strokeColor,
        BankR.attr.mc_strokeWidth,
        BankR.attr.mc_gradientStartColor,
        BankR.attr.mc_gradientEndColor,
        BankR.attr.mc_gradientCenterColor,
        BankR.attr.mc_gradientOrientation,
        BankR.attr.mc_gradientCenterX,
        BankR.attr.mc_gradientCenterY,
        BankR.attr.mc_renderMode,
        BankR.attr.mc_dashWidth,
        BankR.attr.mc_dashGap,
    )

    private const val CONTENT = 0
    private const val RADIUS = 1
    private const val TOP_LEFT = 2
    private const val TOP_RIGHT = 3
    private const val BOTTOM_LEFT = 4
    private const val BOTTOM_RIGHT = 5
    private const val STROKE_COLOR = 6
    private const val STROKE_WIDTH = 7
    private const val GRAD_START = 8
    private const val GRAD_END = 9
    private const val GRAD_CENTER = 10
    private const val GRAD_ORIENTATION = 11
    private const val GRAD_CENTER_X = 12
    private const val GRAD_CENTER_Y = 13
    private const val RENDER_MODE = 14
    private const val DASH_WIDTH = 15
    private const val DASH_GAP = 16

    fun apply(view: View, attrs: AttributeSet?) {
        if (attrs == null) return
        val context = view.context
        context.obtainStyledAttributes(attrs, ATTRS).use { a ->
            buildDrawable(a)?.let { drawable ->
                if (a.getInt(RENDER_MODE, RENDER_BACKGROUND) == RENDER_FOREGROUND) {
                    view.foreground = drawable
                } else {
                    view.background = drawable
                }
            }
        }
    }

    private fun buildDrawable(a: android.content.res.TypedArray): Drawable? {
        // mc_contentColor is color|reference: a plain color becomes a filled rounded rect, while a
        // drawable reference is used as-is because GradientDrawable cannot nest one portably.
        val fillColor = a.getColor(CONTENT, 0)
        val fillDrawable = if (fillColor == 0) a.getDrawable(CONTENT) else null
        val start = a.getColor(GRAD_START, 0)
        val end = a.getColor(GRAD_END, 0)
        val uniform = a.getDimension(RADIUS, 0f)
        val topLeft = a.getDimension(TOP_LEFT, uniform)
        val topRight = a.getDimension(TOP_RIGHT, uniform)
        val bottomLeft = a.getDimension(BOTTOM_LEFT, uniform)
        val bottomRight = a.getDimension(BOTTOM_RIGHT, uniform)
        val strokeColor = a.getColor(STROKE_COLOR, 0)
        val strokeWidth = a.getDimension(STROKE_WIDTH, 0f)
        val dashWidth = a.getDimension(DASH_WIDTH, 0f)
        val dashGap = a.getDimension(DASH_GAP, 0f)
        val styled = uniform > 0f || topLeft > 0f || topRight > 0f || bottomLeft > 0f ||
            bottomRight > 0f || strokeWidth > 0f || start != 0 || end != 0

        if (!styled) return fillDrawable
        if (fillColor == 0 && start == 0 && end == 0 && strokeWidth == 0f) return fillDrawable

        val shape = GradientDrawable()
        shape.shape = GradientDrawable.RECTANGLE
        if (topLeft == topRight && topRight == bottomLeft && bottomLeft == bottomRight) {
            shape.cornerRadius = topLeft
        } else {
            shape.cornerRadii = floatArrayOf(
                topLeft, topLeft, topRight, topRight,
                bottomRight, bottomRight, bottomLeft, bottomLeft,
            )
        }
        when {
            start != 0 && end != 0 -> {
                val center = a.getColor(GRAD_CENTER, 0)
                shape.colors = if (center != 0) intArrayOf(start, center, end) else intArrayOf(start, end)
                shape.gradientType = GradientDrawable.LINEAR_GRADIENT
                shape.orientation = orientationOf(a.getInt(GRAD_ORIENTATION, 0))
                if (a.hasValue(GRAD_CENTER_X) || a.hasValue(GRAD_CENTER_Y)) {
                    shape.setGradientCenter(
                        a.getFloat(GRAD_CENTER_X, 0.5f),
                        a.getFloat(GRAD_CENTER_Y, 0.5f),
                    )
                }
            }
            fillColor != 0 -> shape.color = ColorStateList.valueOf(fillColor)
            start != 0 -> shape.color = ColorStateList.valueOf(start)
            end != 0 -> shape.color = ColorStateList.valueOf(end)
        }
        if (strokeWidth > 0f && strokeColor != 0) {
            if (dashWidth > 0f && dashGap > 0f) {
                shape.setStroke(strokeWidth.toInt(), strokeColor, dashWidth, dashGap)
            } else {
                shape.setStroke(strokeWidth.toInt(), strokeColor)
            }
        }
        return shape
    }

    /** mc_gradientOrientation, verbatim from the bank's enum declaration. */
    private fun orientationOf(value: Int): GradientDrawable.Orientation = when (value) {
        0 -> GradientDrawable.Orientation.TOP_BOTTOM
        1 -> GradientDrawable.Orientation.TR_BL
        2 -> GradientDrawable.Orientation.RIGHT_LEFT
        3 -> GradientDrawable.Orientation.BR_TL
        4 -> GradientDrawable.Orientation.BOTTOM_TOP
        5 -> GradientDrawable.Orientation.BL_TR
        6 -> GradientDrawable.Orientation.LEFT_RIGHT
        else -> GradientDrawable.Orientation.TL_BR
    }
}
