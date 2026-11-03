package com.mc.fastkit.view.host

import android.content.Context
import android.util.AttributeSet
import android.view.View

/**
 * Spacer whose height equals the system bar it stands in for, matching the decompiled classes
 * (both extend View and exist so a constraint chain can stop below/above the bar).
 */
private fun systemBarHeight(context: Context, name: String): Int {
    val id = context.resources.getIdentifier(name, "dimen", "android")
    return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
}

class StatusBarHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
            systemBarHeight(context, "status_bar_height"), MeasureSpec.EXACTLY))
    }
}

class NavigationBarHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
            systemBarHeight(context, "navigation_bar_height"), MeasureSpec.EXACTLY))
    }
}
