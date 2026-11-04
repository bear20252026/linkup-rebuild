package com.youloft.watcher.pages.friend

import android.app.Activity
import com.youloft.linkup.api.dto.FriendRequest
import com.youloft.watcher.pages.friend.dialog.FriendNumLimitDialog
import com.youloft.watcher.pages.friend.dialog.FriendPermissionsSettingsDialog

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

    /** `ChatActivity` — the IM layer is its own card, so only the intent is recorded. */
    fun openChat(userId: Long)

    fun openAgreeAdd()

    fun showFriendNumLimitDialog()

    fun showPermissionSettings(friendUserId: Long)
}

/** Not rebuilt yet: `AddFriendActivity`, `AgreeAddActivity`, `ChatActivity`. */
class DefaultFriendFlowNavigation(
    private val activity: Activity,
) : FriendFlowNavigation {

    override fun openAddFriend(mode: Int) = Unit

    override fun openFriendRequestDetail(request: FriendRequest, friendLimit: Boolean) {
        FriendRequestDetailActivity.start(activity, request, friendLimit)
    }

    override fun openFriendInfo(userId: Long, type: Int, name: String?) {
        FriendInfoActivity.start(activity, userId, type, name)
    }

    override fun openChat(userId: Long) = Unit

    override fun openAgreeAdd() = Unit

    override fun showFriendNumLimitDialog() {
        FriendNumLimitDialog(activity).show()
    }

    override fun showPermissionSettings(friendUserId: Long) {
        // Recovered seed is "everything on", the ✕ is hidden, and source 1 names the request-agree
        // entry. A null result means the user walked away, which the original read as "just finish".
        FriendPermissionsSettingsDialog(
            activity,
            SOURCE_REQUEST_AGREE,
            allSelectedPermissions(),
            showClose = false,
        ) { result ->
            if (result == null) {
                openAgreeAdd()
                activity.finish()
            }
            // A chosen set feeds `PrivacyRepository.updatePermissions`, whose two-phase per-type
            // sync is the privacy card's work; nothing partial is invented here.
        }.show()
    }

    private companion object {
        const val SOURCE_REQUEST_AGREE = 1
    }
}
