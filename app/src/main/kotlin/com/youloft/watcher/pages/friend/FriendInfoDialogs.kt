package com.youloft.watcher.pages.friend

import android.app.Activity
import com.youloft.linkup.api.dto.Permission
import com.youloft.watcher.R
import com.youloft.watcher.pages.common.ConfirmDialog
import com.youloft.watcher.pages.friend.dialog.FriendPermissionsSettingsDialog
import com.youloft.watcher.pages.friend.dialog.UpdateRelationDialog
import com.youloft.watcher.pages.user.dialog.UpdateUsernameDialog

/**
 * The four dialogs this screen opens. Kept as an interface so a test can deliver the user's choice
 * without a touch layer; [RealFriendInfoDialogs] is what production uses.
 */
interface FriendInfoDialogs {
    fun confirmDelete(message: String, onConfirm: () -> Unit)

    fun updateRelation(current: Int, onPicked: (Int) -> Unit)

    fun updateRemark(title: String, current: String, hint: String, onSubmit: (String) -> Unit)

    fun permissionSettings(current: Permission, onResult: (Permission?) -> Unit)
}

/** Wraps the recovered dialog classes for one screen: this activity is the permission dialog's `-1` entry. */
class RealFriendInfoDialogs(
    private val activity: Activity,
) : FriendInfoDialogs {

    override fun confirmDelete(message: String, onConfirm: () -> Unit) {
        ConfirmDialog(activity, message)
            .setConfirmText(R.string.confirm2)
            .setCancelText(R.string.cancel)
            .setOnConfirm(onConfirm)
            .show()
    }

    override fun updateRelation(current: Int, onPicked: (Int) -> Unit) {
        UpdateRelationDialog(activity, current, onPicked).show()
    }

    override fun updateRemark(title: String, current: String, hint: String, onSubmit: (String) -> Unit) {
        // The recovered call site passed alwaysEnabled = true for remarks, so confirm is never dimmed.
        UpdateUsernameDialog(activity, title, current, hint, alwaysEnabled = true, onConfirm = onSubmit).show()
    }

    override fun permissionSettings(current: Permission, onResult: (Permission?) -> Unit) {
        // The recovered call site for this screen hid the close button, so an outside tap is the only
        // way out and the result stays null unless confirm is pressed.
        FriendPermissionsSettingsDialog(
            activity,
            SOURCE_FRIEND_INFO,
            current,
            showClose = false,
            onResult = onResult,
        ).show()
    }

    private companion object {
        const val SOURCE_FRIEND_INFO = -1
    }
}

internal fun allSelectedPermissions(): Permission = Permission(
    sensLocation = true,
    sensTrack = true,
    sensPhoneUse = true,
    sensCall = true,
    sensPhoneState = true,
    sensSensitive = true,
    sensAppUse = true,
    sensLockPhone = true,
    sensRealtimeVoice = true,
    sensScreenShot = true,
)

