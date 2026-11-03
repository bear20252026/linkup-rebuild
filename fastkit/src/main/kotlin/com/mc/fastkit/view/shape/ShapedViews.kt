package com.mc.fastkit.view.shape

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.res.use
import androidx.constraintlayout.widget.ConstraintLayout
import com.mc.fastkit.view.ShapeAttrs
import com.youloft.watcher.res166.R as BankR

/**
 * Shape views expose their parsed `mc_*` style so a bind can change the corners at runtime, which
 * is what the recovered list screens do for their first and last rows.
 */
interface Shaped {
    val shapeAttrs: ShapeAttrs
}

class ShapedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr), Shaped {

    override val shapeAttrs = ShapeAttrs(this, attrs)
}

class ShapedEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr), Shaped {

    override val shapeAttrs = ShapeAttrs(this, attrs)
}

class ShapedFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), Shaped {

    override val shapeAttrs = ShapeAttrs(this, attrs)
}

class ShapedLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr), Shaped {

    override val shapeAttrs = ShapeAttrs(this, attrs)
}

class ShapedRelativeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr), Shaped {

    override val shapeAttrs = ShapeAttrs(this, attrs)
}

class ShapedConstraintLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr), Shaped {

    override val shapeAttrs = ShapeAttrs(this, attrs)
}

/** Rounds the drawable within the view bounds; mc_cornerSize accepts a dimension or a fraction. */
class ShapedImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr), Shaped {

    override val shapeAttrs = ShapeAttrs(this, attrs)

    private var cornerPx = 0f
    private var cornerFraction = 0f

    init {
        if (attrs != null) {
            context.obtainStyledAttributes(attrs, CORNER_ATTRS).use { a ->
                val value = a.peekValue(0)
                if (value?.type == TypedValue.TYPE_FRACTION) {
                    cornerFraction = a.getFloat(0, 0f)
                } else {
                    cornerPx = a.getDimension(0, 0f)
                }
                if (a.getBoolean(1, false)) cornerFraction = 0.5f
            }
            if (cornerPx > 0f || cornerFraction > 0f) {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        val shorter = minOf(view.width, view.height)
                        val radius = when {
                            cornerFraction > 0f -> cornerFraction * shorter
                            else -> cornerPx
                        }.coerceAtMost(shorter / 2f)
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
            }
        }
    }

    private companion object {
        val CORNER_ATTRS = intArrayOf(BankR.attr.mc_cornerSize, BankR.attr.mc_isRounded)
    }
}
