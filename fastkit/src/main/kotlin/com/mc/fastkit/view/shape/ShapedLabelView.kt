package com.mc.fastkit.view.shape

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.content.res.use
import com.mc.fastkit.view.ShapeStyle
import com.youloft.watcher.res166.R as BankR

/**
 * Image + title + description composite, rebuilt from the decompiled class, which likewise holds
 * one ImageView and two TextViews. `mc_font` is accepted but not applied yet: loading a font
 * resource needs the typeface cache the rest of FastKit carried, and silently faking it would be
 * worse than leaving the text at the default face.
 */
class ShapedLabelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {

    val imageView: ImageView = AppCompatImageView(context).apply { id = ViewCompat.generateViewId() }
    val titleView: TextView = AppCompatTextView(context).apply { id = ViewCompat.generateViewId() }
    val descriptionView: TextView = AppCompatTextView(context).apply { id = ViewCompat.generateViewId() }

    init {
        ShapeStyle.apply(this, attrs)

        addView(imageView, imageParams())
        addView(titleView, titleParams())
        addView(descriptionView, descriptionParams())

        if (attrs != null) {
            context.obtainStyledAttributes(attrs, ATTRS).use { a ->
                a.getDrawable(0)?.let { imageView.setImageDrawable(it) }
                a.getText(1)?.let { titleView.text = it }
                a.getColor(2, 0).takeIf { it != 0 }?.let { titleView.setTextColor(it) }
                if (a.hasValue(3)) titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, a.getDimension(3, 0f))
            }
        }
    }

    private fun imageParams() = LayoutParams(0, 0).apply {
        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        dimensionRatio = "h,1:1"
    }

    private fun titleParams() = LayoutParams(0, LayoutParams.WRAP_CONTENT).apply {
        startToEnd = imageView.id
        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        bottomToTop = descriptionView.id
    }

    private fun descriptionParams() = LayoutParams(0, LayoutParams.WRAP_CONTENT).apply {
        startToEnd = imageView.id
        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
    }

    private companion object {
        val ATTRS = intArrayOf(
            BankR.attr.mc_image,
            BankR.attr.mc_text,
            BankR.attr.mc_textColor,
            BankR.attr.mc_textSize,
        )
    }
}
