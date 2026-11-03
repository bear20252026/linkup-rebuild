package com.youloft.watcher.view

import android.content.Context
import android.util.AttributeSet

/**
 * The recovered class is a vendored copy of kyleduo's SwitchButton: the layouts drive it with the
 * same `sb_*` attributes (sb_background, sb_checked, sb_checked_color, sb_uncheck_color,
 * sb_border_width, sb_show_indicator). Now that the upstream artifact is a real dependency, this
 * delegates to it instead of re-implementing 29KB of self-drawn toggle code.
 */
class SwitchButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : com.kyleduo.switchbutton.SwitchButton(context, attrs, defStyleAttr)
