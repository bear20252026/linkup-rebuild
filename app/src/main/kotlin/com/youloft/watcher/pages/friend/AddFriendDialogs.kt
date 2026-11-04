package com.youloft.watcher.pages.friend

import android.app.Activity
import com.youloft.linkup.api.dto.Friend
import com.youloft.linkup.api.dto.Permission
import com.youloft.watcher.pages.friend.dialog.FriendPermissionsSettingsDialog
import com.youloft.watcher.pages.friend.dialog.VerifyInfoDialog

/**
 * The two sheets this screen opens, as an interface so a test can deliver the user's choice without a
 * touch layer; [RealAddFriendDialogs] is what production uses.
 */
interface AddFriendDialogs {
    /**
     * The "核对信息" sheet. `type` is where the lookup came from (扫一扫 1, 匹配码 2, 手机号 3,
     * 通讯录 4, H5 5) and only reaches the analytics label; `onConfirm` gets the relation the user
     * picked plus the callback the caller uses to close the sheet itself.
     */
    fun verifyInfo(
        friend: Friend,
        type: Int,
        mySex: Int,
        onVipLabelClick: (source: Int, label: String) -> Unit,
        onConfirm: (Int, () -> Unit) -> Unit,
    )

    /** Seeded with "everything on", and the ✕ stays visible here — unlike the request-agree entry. */
    fun permissionSettings(source: Int, onResult: (Permission?) -> Unit, onDismissed: () -> Unit)
}

class RealAddFriendDialogs(
    private val activity: Activity,
) : AddFriendDialogs {

    override fun verifyInfo(
        friend: Friend,
        type: Int,
        mySex: Int,
        onVipLabelClick: (source: Int, label: String) -> Unit,
        onConfirm: (Int, () -> Unit) -> Unit,
    ) {
        VerifyInfoDialog(activity, friend, type, mySex, onVipLabelClick, onConfirm).show()
    }

    override fun permissionSettings(source: Int, onResult: (Permission?) -> Unit, onDismissed: () -> Unit) {
        FriendPermissionsSettingsDialog(
            activity,
            source,
            allSelectedPermissions(),
            showClose = true,
            onDismissed = onDismissed,
            onResult = onResult,
        ).show()
    }
}
