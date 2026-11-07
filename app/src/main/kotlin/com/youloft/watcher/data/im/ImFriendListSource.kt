package com.youloft.watcher.data.im

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.youloft.linkup.im.ImClient
import com.youloft.linkup.im.ImFriendEvent
import com.youloft.linkup.net.ApiResponse
import com.youloft.linkup.net.LinkUpApi
import com.youloft.watcher.bean.CmdFriendBean
import com.youloft.watcher.data.FriendEvents
import com.youloft.watcher.data.FriendListSource
import com.youloft.watcher.data.local.FriendCache
import com.youloft.watcher.data.local.KeyValueStores
import com.youloft.watcher.data.local.UserCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The friend-list source the IM card replaces the placeholder with — and the piece that finally gives
 * `friendList` its writer (recovered 待验 9's gap): OpenIM pushes a mutation, this class re-pulls
 * `api/relation/list`, **writes the cache** through [FriendCache], and publishes the same LiveData the
 * friend screens already observe.
 *
 * ## Why a refresh, not a merge
 *
 * OpenIM knows the friendship; the LinkUp relation carries what OpenIM's model cannot answer — the 13
 * keys of `FriendList.Info` the rebuilt server deliberately never invents (待验 53). Merging the two would
 * put this class in the business of guessing those fields, so every event becomes one authoritative
 * refresh: the server's answer is the list, the cache is its copy, and the IM event only decides *when*.
 * The event is also forwarded as a `CmdFriendBean` with the recovered kind numbers (ADD=1/DELETE=2/UPDATE=3),
 * which is the shape `manager.u.x` consumed.
 *
 * The `friend_delete` broadcast ([FriendEvents]) lands here too: deletion happens on the friend-info
 * screen, the IM push may arrive separately, and both must end in one refreshed cache.
 */
open class ImFriendListSource(
    /** The authoritative read: `api/relation/list`. A function, so tests proxy the api like the fixtures do. */
    private val refresh: suspend () -> ApiResponse<com.youloft.linkup.api.dto.FriendList>,
    /**
     * The stores, the account reader and the transport, as **providers**: constructing this source must
     * not touch MMKV or the IM SDK, because `friendList` is a graph lazy that screens evaluate as soon
     * as they render — a bare application instance (widgets, tests) has neither a base context nor a
     * loaded SDK. All three resolve on [start].
     */
    storesProvider: () -> KeyValueStores,
    usersProvider: () -> UserCache,
    imClientProvider: () -> ImClient,
    private val scope: CoroutineScope,
    private val gson: Gson = Gson(),
) : FriendListSource {

    private val stores by lazy { storesProvider() }
    private val users by lazy { usersProvider() }
    private val imClient by lazy { imClientProvider() }
    /** Lazy like its inputs: touching MMKV is [start]'s job, not the constructor's. */
    private val cache: FriendCache by lazy { FriendCache(stores, users, gson) }
    private val friendsData = MutableLiveData<List<com.youloft.linkup.api.dto.FriendList.Info>>(emptyList())
    private val commandData = MutableLiveData<CmdFriendBean?>()

    /**
     * Starts listening; called by the graph right after construction. The cache read happens here, not
     * in the constructor, because construction runs the moment any screen touches `friendList` — the
     * same lazy that a bare `LinkUpApplication()` (tests, widgets) evaluates without a base context, and
     * the store is the first thing that would ask for one.
     */
    fun start() {
        friendsData.postValue(cache.cachedFriends())
        imClient.friendEvents
            .onEach { onImEvent(it) }
            .launchIn(scope)
        FriendEvents.friendDeleted
            .onEach { userId -> refresh(ImFriendEvent.DELETE, userId) }
            .launchIn(scope)
    }

    override val friends: LiveData<List<com.youloft.linkup.api.dto.FriendList.Info>> = friendsData
    override val friendCommand: LiveData<CmdFriendBean?> = commandData

    override fun clearFriendCommand() {
        commandData.value = null
    }

    override fun mainUserId(): Long = users.userId()

    override fun handleFriendCommand(context: Context, command: CmdFriendBean) {
        // The commands this source emits come from the pushes it already handles; a screen-driven
        // command has nothing to add until the screens write back through the repository themselves.
        scope.launch { refresh(commandToKind(command), command.userid) }
    }

    private suspend fun onImEvent(event: ImFriendEvent) {
        refresh(event.kind, event.userId)
    }

    /** The one write path: pull, cache, publish, announce. Failures keep the previous list on screen. */
    private suspend fun refresh(kind: Int, userId: Long) {
        val response = runCatching { refresh() }.getOrNull() ?: return
        if (!response.isSuccess) return
        val list = response.data?.list.orEmpty().filterNotNull()
        saveFriends(list)
        friendsData.postValue(list)
        commandData.postValue(CmdFriendBean(type = kind, userid = userId))
    }

    /** The write side of the friend cache — the recovered `CacheUtils` wrote the same key with Gson. */
    private fun saveFriends(list: List<com.youloft.linkup.api.dto.FriendList.Info>) {
        stores.forUser(users.userId()).putString(FriendCache.KEY_FRIENDS, gson.toJson(list))
    }

    private fun commandToKind(command: CmdFriendBean): Int = command.type

    companion object {
        /** The recovered `CmdFriendBean` kind numbers, mirrored from `bean/CmdFriendBean.kt`. */
        const val KIND_ADD = CmdFriendBean.ADD
        const val KIND_DELETE = CmdFriendBean.DELETE
        const val KIND_UPDATE = CmdFriendBean.UPDATE
    }
}
