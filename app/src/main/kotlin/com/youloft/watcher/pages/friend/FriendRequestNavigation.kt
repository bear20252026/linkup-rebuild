package com.youloft.watcher.pages.friend

import com.youloft.linkup.api.dto.FriendRequest

/**
 * The screens this list hands control to. None of them is rebuilt yet, so the default object only
 * marks the intent; each method gets its real `startActivity` when that screen's card is done.
 */
interface FriendRequestNavigation {
    fun openAddFriend()

    fun openFriendRequestDetail(request: FriendRequest, friendLimit: Boolean)

    fun openFriendInfo(userId: Long)

    fun openAgreeAdd()

    fun showFriendNumLimitDialog()

    fun showPermissionSettings(friendUserId: Long)
}

/** `AddFriendActivity`, `FriendRequestDetailActivity`, `FriendInfoActivity`, `AgreeAddActivity`,
 * `FriendNumLimitDialog` and `FriendPermissionsSettingsDialog` are all still on the backlog. */
object NoopFriendRequestNavigation : FriendRequestNavigation {
    override fun openAddFriend() = Unit

    override fun openFriendRequestDetail(request: FriendRequest, friendLimit: Boolean) = Unit

    override fun openFriendInfo(userId: Long) = Unit

    override fun openAgreeAdd() = Unit

    override fun showFriendNumLimitDialog() = Unit

    override fun showPermissionSettings(friendUserId: Long) = Unit
}
