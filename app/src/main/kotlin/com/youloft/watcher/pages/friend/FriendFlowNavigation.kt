package com.youloft.watcher.pages.friend

import android.app.Activity
import com.youloft.linkup.api.dto.FriendRequest

/**
 * Screens the friend flow hands control to. The mode/type integers are the ones each recovered
 * call site passed to the target screen, so they are kept even where nothing reads them yet —
 * they are how the destination knows where the user came from.
 *
 * The detail screen is rebuilt, so it launches for real; the rest stay empty until their own
 * cards land, which keeps the recovered control flow readable instead of pretending they exist.
 */
interface FriendFlowNavigation {
    /** `AddFriendActivity` modes: 8 from the request list header, 6 from the friend-list button, 0 from its empty view. */
    fun openAddFriend(mode: Int)

    fun openFriendRequestDetail(request: FriendRequest, friendLimit: Boolean)

    /** `FriendInfoActivity` types: 0 from the request list, 2 from my-friend list. */
    fun openFriendInfo(userId: Long, type: Int, name: String?)

    fun openAgreeAdd()

    fun showFriendNumLimitDialog()

    fun showPermissionSettings(friendUserId: Long)
}

/** Not rebuilt yet: `AddFriendActivity`, `FriendInfoActivity`, `AgreeAddActivity`,
 * `FriendNumLimitDialog`, `FriendPermissionsSettingsDialog`. */
class DefaultFriendFlowNavigation(
    private val activity: Activity,
) : FriendFlowNavigation {

    override fun openAddFriend(mode: Int) = Unit

    override fun openFriendRequestDetail(request: FriendRequest, friendLimit: Boolean) {
        FriendRequestDetailActivity.start(activity, request, friendLimit)
    }

    override fun openFriendInfo(userId: Long, type: Int, name: String?) = Unit

    override fun openAgreeAdd() = Unit

    override fun showFriendNumLimitDialog() = Unit

    override fun showPermissionSettings(friendUserId: Long) = Unit
}
