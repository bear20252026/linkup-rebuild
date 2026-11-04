package com.youloft.watcher.pages.friend.dialog

import android.content.Context
import com.mc.fastkit.dialog.BindingDialog
import com.youloft.watcher.databinding.DialogUpdateRelationBinding
import com.youloft.watcher.ext.onIntervalClick

/**
 * Recovered from `pages.friend.dialog.UpdateRelationDialog`: the picker starts on the current
 * relation, the tick closes without applying, and confirm closes first and then reports the choice.
 */
class UpdateRelationDialog(
    context: Context,
    relation: Int,
    private val onConfirm: (Int) -> Unit,
) : BindingDialog<DialogUpdateRelationBinding>(context) {

    private var picked = relation

    override fun onInit(binding: DialogUpdateRelationBinding) {
        binding.relationButton.setRelation(picked)
        binding.relationButton.onRelationChanged = { picked = it }
        binding.ivClose.onIntervalClick { dismissDelayed() }
        binding.tvConfirm.onIntervalClick {
            dismissDelayed()
            onConfirm(picked)
        }
    }
}
