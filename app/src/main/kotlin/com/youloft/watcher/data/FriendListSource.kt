package com.youloft.watcher.data

import android.content.Context
import androidx.lifecycle.LiveData
import com.youloft.linkup.api.dto.FriendList
import com.youloft.watcher.bean.CmdFriendBean

/**
 * The slice of `manager.u` (the IM-backed friend manager) that friend screens actually observe.
 * The recovered screens do not read the friend list from HTTP: the push layer keeps a live list and
 * a command channel, and this interface is exactly those two plus the two side effects.
 */
interface FriendListSource {
    val friends: LiveData<List<FriendList.Info>>

    /** A friend-change push; the screen clears it once handled, which is how the original used it. */
    val friendCommand: LiveData<CmdFriendBean?>

    fun clearFriendCommand()

    /** `manager.u.x(context, command)` — refresh local friend data for this command. */
    fun handleFriendCommand(context: Context, command: CmdFriendBean)

    /** `CacheUtils.y0().mainUser` — the account the list puts first. */
    fun mainUserId(): Long
}

/**
 * Placeholder until the IM/cache card lands: it holds the list so screens are exercisable today,
 * and does nothing for pushes rather than pretending to know how the socket layer refreshed state.
 */
open class PendingFriendListSource : FriendListSource {
    private val friendsData = androidx.lifecycle.MutableLiveData<List<FriendList.Info>>(emptyList())
    private val commandData = androidx.lifecycle.MutableLiveData<CmdFriendBean?>()

    override val friends: LiveData<List<FriendList.Info>> = friendsData
    override val friendCommand: LiveData<CmdFriendBean?> = commandData

    override fun clearFriendCommand() {
        commandData.value = null
    }

    override fun handleFriendCommand(context: android.content.Context, command: CmdFriendBean) = Unit

    override fun mainUserId(): Long = 0L

    /** Lets a local cache or a test push a new list through the same channel the screens observe. */
    fun publish(list: List<FriendList.Info>) {
        friendsData.value = list
    }

    fun publishCommand(command: CmdFriendBean?) {
        commandData.value = command
    }
}
