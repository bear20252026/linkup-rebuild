package com.mc.fastkit.view.shadow

import android.content.Context
import android.graphics.Outline
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.res.use
import com.mc.fastkit.view.ShapeStyle
import com.youloft.watcher.res166.R as BankR

/**
 * Elevation-backed corners. The originals render a shadow themselves; this uses the platform's
 * outline shadows, which only honor an explicit color from API 29 up -- below that the elevation
 * still applies and only the tint is lost.
 */
private fun applyShadow(view: View, attrs: AttributeSet?) {
    if (attrs == null) return
    view.context.obtainStyledAttributes(attrs, ATTRS).use { a ->
        val elevation = a.getDimension(0, 0f)
        val corner = a.getDimension(1, 0f)
        ShapeStyle.apply(view, attrs)
        if (elevation > 0f) view.elevation = elevation
        if (corner > 0f) {
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, corner)
                }
            }
        }
        val color = a.getColor(2, 0)
        if (color != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.outlineSpotShadowColor = color
            view.outlineAmbientShadowColor = color
        }
    }
}

private val ATTRS = intArrayOf(
    BankR.attr.mc_shadowElevation,
    BankR.attr.mc_cornerSize,
    BankR.attr.mc_shadowColor,
)

class ShadowFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    init {
        applyShadow(this, attrs)
    }
}

class ShadowLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    init {
        applyShadow(this, attrs)
    }
}

class ShadowTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    init {
        applyShadow(this, attrs)
    }
}
