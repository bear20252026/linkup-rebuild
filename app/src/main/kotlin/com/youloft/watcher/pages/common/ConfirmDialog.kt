package com.youloft.watcher.pages.common

import android.content.Context
import android.view.View
import com.mc.fastkit.dialog.BindingDialog
import com.youloft.watcher.R
import com.youloft.watcher.databinding.DialogConfirmBinding
import com.youloft.watcher.ext.onIntervalClick

/**
 * The shared "are you sure" dialog, recovered from `pages.common.ConfirmDialog`: title, optional
 * content line, cancel on the left, confirm on the right, both buttons on the recovered 500ms
 * interval click, and either button dismissing.
 */
class ConfirmDialog(
    context: Context,
    private val title: String,
) : BindingDialog<DialogConfirmBinding>(context) {

    private var cancelText = context.getString(R.string.cancel)
    private var confirmText = context.getString(R.string.confirm)
    private var content = ""
    private var onConfirmListener: (() -> Unit)? = null
    private var onCancelListener: (() -> Unit)? = null

    init {
        setCancelable(true)
    }

    fun setConfirmText(resId: Int) = apply { confirmText = getContext().getString(resId) }

    fun setCancelText(resId: Int) = apply { cancelText = getContext().getString(resId) }

    fun setContent(resId: Int) = apply { content = getContext().getString(resId) }

    fun setOnConfirm(action: () -> Unit) = apply { onConfirmListener = action }

    fun setOnCancel(action: () -> Unit) = apply { onCancelListener = action }

    override fun onInit(binding: DialogConfirmBinding) {
        binding.tvTitle.text = title
        binding.tvCancel.text = cancelText
        binding.tvConfirm.text = confirmText
        if (content.isNotEmpty()) {
            binding.tvContent.visibility = View.VISIBLE
            binding.tvContent.text = content
        }
        binding.tvCancel.onIntervalClick {
            onCancelListener?.invoke()
            dismissDelayed()
        }
        binding.tvConfirm.onIntervalClick {
            onConfirmListener?.invoke()
            dismissDelayed()
        }
    }
}
