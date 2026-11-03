package com.youloft.watcher.view.vip

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.Checkable
import android.widget.LinearLayout
import com.youloft.watcher.R

/**
 * The "已阅读并同意协议" row, rebuilt as the 1.6.6 original: a LinearLayout that is itself
 * Checkable and delegates to the CheckBox inside view_vip_policy. The agreement spans are wired
 * by the VIP screen that owns this view, not invented here.
 */
class VipPolicyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr), Checkable {

    private val checkBox: CheckBox?
        get() = findViewById(R.id.cb_read_vip_policy)

    init {
        orientation = HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.view_vip_policy, this, true)
    }

    override fun isChecked(): Boolean = checkBox?.isChecked ?: false

    override fun setChecked(checked: Boolean) {
        checkBox?.isChecked = checked
    }

    override fun toggle() {
        checkBox?.toggle()
    }
}
