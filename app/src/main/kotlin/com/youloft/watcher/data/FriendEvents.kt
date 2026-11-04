package com.youloft.watcher.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The app-wide notifications the recovered code sent through `LiveDataBus` topic names. Typed here
 * so a screen can publish without knowing its subscribers; the topic strings are kept in the
 * comments because the decompiled call sites are keyed by them.
 */
object FriendEvents {
    private val _friendDeleted = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** `ag.b.s` = "friend_delete", published with the deleted friend's id. */
    val friendDeleted: SharedFlow<Long> = _friendDeleted

    fun publishFriendDeleted(userId: Long) {
        _friendDeleted.tryEmit(userId)
    }
}
