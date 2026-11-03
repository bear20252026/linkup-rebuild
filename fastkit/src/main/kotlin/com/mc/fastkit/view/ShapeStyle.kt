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
 * Rebuilt from the 1.5.6 decompiled `com.mc.fastkit.view.shape` classes and their `dc.a` attrs
 * object, which screens mutate at bind time (list rows round their own corners).
 *
 * The shipping APK keeps these as flat top-level `<attr name="mc_*"/>` entries (declare-styleable
 * is compile-time only), so the views read them through their own attribute array instead of
 * `R.styleable.ShapedXxx`. The enum values below are taken from the bank's attrs.xml, not guessed.
 */
object ShapeStyle {
    const val RENDER_BACKGROUND = 0
    const val RENDER_FOREGROUND = 1

    /** Used by helpers that only need the XML style once and never mutate it. */
    fun apply(view: View, attrs: AttributeSet?) {
        ShapeAttrs(view, attrs)
    }
}

/**
 * The parsed `mc_*` style of one shaped view. Setters return `this` so a bind can chain them and
 * then call [render], exactly like the recovered `dc.a` builder (`k/t/l/r` + `w0`).
 */
class ShapeAttrs internal constructor(
    private val view: View,
    attrs: AttributeSet?,
) {
    private var contentColor = 0
    private var contentDrawable: Drawable? = null
    private var topLeft = 0f
    private var topRight = 0f
    private var bottomLeft = 0f
    private var bottomRight = 0f
    private var strokeColor = 0
    private var strokeWidth = 0f
    private var dashWidth = 0f
    private var dashGap = 0f
    private var gradientStart = 0
    private var gradientEnd = 0
    private var gradientCenter = 0
    private var gradientOrientation = 0
    private var gradientCenterX = 0.5f
    private var gradientCenterY = 0.5f
    private var hasGradientCenter = false
    private var renderMode = ShapeStyle.RENDER_BACKGROUND

    init {
        if (attrs != null) {
            view.context.obtainStyledAttributes(attrs, ATTRS).use { a ->
                contentColor = a.getColor(CONTENT, 0)
                if (contentColor == 0) contentDrawable = a.getDrawable(CONTENT)
                val uniform = a.getDimension(RADIUS, 0f)
                topLeft = a.getDimension(TOP_LEFT, uniform)
                topRight = a.getDimension(TOP_RIGHT, uniform)
                bottomLeft = a.getDimension(BOTTOM_LEFT, uniform)
                bottomRight = a.getDimension(BOTTOM_RIGHT, uniform)
                strokeColor = a.getColor(STROKE_COLOR, 0)
                strokeWidth = a.getDimension(STROKE_WIDTH, 0f)
                dashWidth = a.getDimension(DASH_WIDTH, 0f)
                dashGap = a.getDimension(DASH_GAP, 0f)
                gradientStart = a.getColor(GRAD_START, 0)
                gradientEnd = a.getColor(GRAD_END, 0)
                gradientCenter = a.getColor(GRAD_CENTER, 0)
                gradientOrientation = a.getInt(GRAD_ORIENTATION, 0)
                hasGradientCenter = a.hasValue(GRAD_CENTER_X) || a.hasValue(GRAD_CENTER_Y)
                gradientCenterX = a.getFloat(GRAD_CENTER_X, 0.5f)
                gradientCenterY = a.getFloat(GRAD_CENTER_Y, 0.5f)
                renderMode = a.getInt(RENDER_MODE, ShapeStyle.RENDER_BACKGROUND)
            }
            render()
        }
    }

    fun setRadius(radius: Float) = apply {
        setTopLeftRadius(radius)
        setTopRightRadius(radius)
        setBottomLeftRadius(radius)
        setBottomRightRadius(radius)
    }

    fun setTopLeftRadius(radius: Float) = apply { topLeft = radius }

    fun setTopRightRadius(radius: Float) = apply { topRight = radius }

    fun setBottomLeftRadius(radius: Float) = apply { bottomLeft = radius }

    fun setBottomRightRadius(radius: Float) = apply { bottomRight = radius }

    fun setContentColor(color: Int) = apply {
        contentColor = color
        contentDrawable = null
    }

    fun setContentDrawable(drawable: Drawable?) = apply {
        contentColor = 0
        contentDrawable = drawable
    }

    /** Rebuilds the drawable from the current values and puts it back on the view. */
    fun render() {
        val context = view.context
        val drawable = buildDrawable(context)
        if (renderMode == ShapeStyle.RENDER_FOREGROUND) {
            view.foreground = drawable
        } else {
            view.background = drawable
        }
    }

    private fun buildDrawable(context: Context): Drawable? {
        // mc_contentColor is color|reference: a plain color becomes a filled rounded rect, while a
        // drawable reference is used as-is because GradientDrawable cannot nest one portably.
        val styled = topLeft > 0f || topRight > 0f || bottomLeft > 0f || bottomRight > 0f ||
            strokeWidth > 0f || gradientStart != 0 || gradientEnd != 0
        if (!styled) return contentDrawable
        if (contentColor == 0 && gradientStart == 0 && gradientEnd == 0 && strokeWidth == 0f) {
            return contentDrawable
        }

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
            gradientStart != 0 && gradientEnd != 0 -> {
                shape.colors =
                    if (gradientCenter != 0) {
                        intArrayOf(gradientStart, gradientCenter, gradientEnd)
                    } else {
                        intArrayOf(gradientStart, gradientEnd)
                    }
                shape.gradientType = GradientDrawable.LINEAR_GRADIENT
                shape.orientation = orientationOf(gradientOrientation)
                if (hasGradientCenter) shape.setGradientCenter(gradientCenterX, gradientCenterY)
            }
            contentColor != 0 -> shape.color = ColorStateList.valueOf(contentColor)
            gradientStart != 0 -> shape.color = ColorStateList.valueOf(gradientStart)
            gradientEnd != 0 -> shape.color = ColorStateList.valueOf(gradientEnd)
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

    private companion object {
        val ATTRS = intArrayOf(
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
    }
}
