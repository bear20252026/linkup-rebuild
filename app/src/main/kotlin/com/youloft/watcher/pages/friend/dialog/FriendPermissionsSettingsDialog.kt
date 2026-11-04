package com.youloft.watcher.pages.friend.dialog

import android.content.Context
import android.view.View
import com.mc.fastkit.dialog.BindingDialog
import com.youloft.linkup.api.dto.Permission
import com.youloft.watcher.databinding.DialogFriendPermissionsBinding
import com.youloft.watcher.ext.onIntervalClick

/**
 * Recovered from `pages.friend.dialog.FriendPermissionsSettingsDialog`. The chosen set is reported
 * when the dialog dismisses — so closing with the ✕ reports `null`, which is how the calling screens
 * distinguish "the user decided" from "the user walked away".
 *
 * `cb_sens_screen_shot` exists in this layout but the recovered dialog never reads or writes it:
 * it belongs to the tenth permission the newer bean added. Left untouched rather than invented.
 */
class FriendPermissionsSettingsDialog(
    context: Context,
    private val source: Int,
    private val initial: Permission?,
    private val showClose: Boolean = true,
    private val onDismissed: () -> Unit = {},
    private val onResult: (Permission?) -> Unit,
) : BindingDialog<DialogFriendPermissionsBinding>(context) {

    private var chosen: Permission? = null

    private var dismissReported = false

    override fun onInit(binding: DialogFriendPermissionsBinding) {
        // Registered first, because the recovered class reported on dismiss through an override: a
        // dialog that dismisses itself for having nothing to show still has to answer its caller.
        setOnDismissListener {
            if (!dismissReported) {
                dismissReported = true
                onResult(chosen)
            }
            // 1.6.6 also hung a second listener on this dialog from the add-friend screen, to say
            // "申请已发送" once the consent sheet went away. Callers get the same hook instead of
            // their own listener, which would replace the reporting one above.
            onDismissed()
        }
        if (initial == null) {
            dismissDelayed()
            return
        }
        binding.ivClose.visibility = if (showClose) View.VISIBLE else View.GONE
        with(binding) {
            cbSensLocation.isChecked = initial.sensLocation
            cbSensTrack.isChecked = initial.sensTrack
            cbSensPhoneUse.isChecked = initial.sensPhoneUse
            cbSensPhoneState.isChecked = initial.sensPhoneState
            cbSensSensitive.isChecked = initial.sensSensitive
            cbSensCall.isChecked = initial.sensCall
            cbSensAppUse.isChecked = initial.sensAppUse
            cbSensLockPhone.isChecked = initial.sensLockPhone
            cbSensVoice.isChecked = initial.sensRealtimeVoice
        }
        binding.ivClose.onIntervalClick { dismissDelayed() }
        binding.tvConfirm.onIntervalClick {
            chosen = Permission(
                sensLocation = binding.cbSensLocation.isChecked,
                sensTrack = binding.cbSensTrack.isChecked,
                sensPhoneUse = binding.cbSensPhoneUse.isChecked,
                sensCall = binding.cbSensCall.isChecked,
                sensPhoneState = binding.cbSensPhoneState.isChecked,
                sensSensitive = binding.cbSensSensitive.isChecked,
                sensAppUse = binding.cbSensAppUse.isChecked,
                sensLockPhone = binding.cbSensLockPhone.isChecked,
                sensRealtimeVoice = binding.cbSensVoice.isChecked,
            )
            dismissDelayed()
        }
    }

    /** The entry label the recovered dialog reported alongside its analytics event. */
    internal fun analyticsLabel(): String = when (source) {
        1 -> "好友申请-同意按钮"
        2 -> "扫一扫"
        3 -> "搜索匹配码"
        4 -> "搜索手机号"
        5 -> "手机通讯录"
        6 -> "H5"
        else -> ""
    }
}
