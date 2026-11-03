package com.youloft.watcher.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.use
import com.youloft.watcher.R

/**
 * Containers that render their own recovered layout instead of requiring every screen to include it.
 * Base classes and inflated layouts match the decompiled originals; the business methods were
 * renamed to single letters by R8, so they are left to the screen cards that restore the call
 * sites rather than invented here.
 */

class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {

    var avatarSizePx = 0f
        private set
    var isEditTagVisible = true
        private set
    var isStrokeVisible = false
        private set

    init {
        attrs?.let { a ->
            context.obtainStyledAttributes(a, ATTRS).use { t ->
                avatarSizePx = t.getDimension(0, 0f)
                isEditTagVisible = t.getBoolean(1, true)
                isStrokeVisible = t.getBoolean(2, false)
            }
        }
        LayoutInflater.from(context).inflate(R.layout.view_avatar, this, true)
    }

    private companion object {
        val ATTRS = intArrayOf(R.attr.avatarSize, R.attr.editTag_visible, R.attr.stroke_visible)
    }
}

class PayTypeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        LayoutInflater.from(context).inflate(R.layout.view_pay_type, this, true)
    }
}

class PswInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        LayoutInflater.from(context).inflate(R.layout.view_psw_input, this, true)
    }
}

class RelationButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {

    init {
        LayoutInflater.from(context).inflate(R.layout.view_relation_button, this, true)
    }
}

class AutoReportSettingsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        LayoutInflater.from(context).inflate(R.layout.view_auto_report_settings, this, true)
    }
}

class ChatPhoneStausView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        LayoutInflater.from(context).inflate(R.layout.view_chat_phone_status, this, true)
    }
}
