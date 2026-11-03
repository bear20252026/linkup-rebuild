package com.youloft.watcher.pages.friend

import android.app.Activity
import com.youloft.linkup.api.dto.FriendRequest

/**
 * The screens this flow hands control to. The detail screen is rebuilt, so it launches for real;
 * the rest are still on the backlog and their methods stay empty until each screen lands, which
 * keeps the recovered control flow readable instead of pretending the targets exist.
 */
interface FriendRequestNavigation {
    fun openAddFriend()

    fun openFriendRequestDetail(request: FriendRequest, friendLimit: Boolean)

    fun openFriendInfo(userId: Long)

    fun openAgreeAdd()

    fun showFriendNumLimitDialog()

    fun showPermissionSettings(friendUserId: Long)
}

/** Screens not rebuilt yet: `AddFriendActivity`, `FriendInfoActivity`, `AgreeAddActivity`,
 * `FriendNumLimitDialog`, `FriendPermissionsSettingsDialog`. */
class DefaultFriendRequestNavigation(
    private val activity: Activity,
) : FriendRequestNavigation {

    override fun openAddFriend() = Unit

    override fun openFriendRequestDetail(request: FriendRequest, friendLimit: Boolean) {
        FriendRequestDetailActivity.start(activity, request, friendLimit)
    }

    override fun openFriendInfo(userId: Long) = Unit

    override fun openAgreeAdd() = Unit

    override fun showFriendNumLimitDialog() = Unit

    override fun showPermissionSettings(friendUserId: Long) = Unit
}
