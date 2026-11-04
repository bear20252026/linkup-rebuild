package com.youloft.watcher.pages.friend.dialog

import android.content.Context
import com.mc.fastkit.dialog.BindingDialog
import com.youloft.watcher.databinding.DialogFriendNumLimitBinding
import com.youloft.watcher.ext.onIntervalClick

/**
 * Recovered from `pages.friend.dialog.FriendNumLimitDialog`: the "you hit the friend quota" upsell.
 * The VIP tile dismissed and opened the purchase page (recovered call site passed source 15);
 * [onVipClick] carries that intent because the VIP page is its own card.
 */
class FriendNumLimitDialog(
    context: Context,
    private val onVipClick: () -> Unit = {},
) : BindingDialog<DialogFriendNumLimitBinding>(context) {

    override fun onInit(binding: DialogFriendNumLimitBinding) {
        binding.tvVip.onIntervalClick {
            dismissDelayed()
            onVipClick()
        }
        binding.ivClose.onIntervalClick { dismissDelayed() }
    }
}
