package com.youloft.linkup.im

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one IM interface. Everything above it — the friend-list cache, the chat screen, the reminders —
 * compiles without knowing whether the transport is OpenIM, a test double, or nothing at all.
 *
 * The shape follows what the LinkUp layer actually consumes, which is the recovered app's own surface:
 * friend-list mutations arrive as [ImFriendEvent] commands that map onto the recovered `CmdFriendBean`
 * types (ADD/DELETE/UPDATE), and the chat screen needs exactly [messages], [history] and [sendText] —
 * voice, screenshots and the camera are later cards with their own routes (the realtime-voice and
 * screenshot families) and are deliberately not part of this interface yet.
 *
 * ## The decision this module embodies
 *
 * The transport is the OpenIM Android SDK talking to the rebuilt stack's OpenIMServer directly — a
 * product decision made this round (原版环信 SDK 拿不到，OpenIMServer 是重建后端已跑通的那一半). The
 * token never comes from a constant: [login] is handed what `api/user/im_token` answered, keyed to the
 * signed-in LinkUp account, and the ws address travels in the same response.
 */
interface ImClient {
    /** Connection lifecycle, collected by the session layer and shown by the chat screen. */
    val connectionState: StateFlow<ImConnection>

    /** Friend mutations OpenIM pushed; consumed by the friend-list cache writer. */
    val friendEvents: SharedFlow<ImFriendEvent>

    /** Incoming messages; consumed by the chat screen and the session list. */
    val messages: SharedFlow<ImMessage>

    /**
     * Initializes the SDK (once per process) and logs the account in. `imUserId`/`token`/`wsUrl` are the
     * `api/user/im_token` response verbatim; the caller does not synthesize any of them.
     */
    suspend fun login(imUserId: String, token: String, wsUrl: String)

    /** Logs out and releases the SDK session. Safe when never logged in. */
    suspend fun logout()

    /** The conversation list, newest first, mapped onto the neutral model. */
    suspend fun conversations(): List<ImConversation>

    /**
     * The most recent [count] messages with one peer, oldest first. Best effort: a transport that
     * answers nothing returns an empty list rather than throwing, because an empty chat that can retry
     * beats a chat screen that crashed.
     */
    suspend fun history(peerUserId: Long, count: Int): List<ImMessage>

    /** Sends one text message. Throws on transport failure; the caller decides what the user sees. */
    suspend fun sendText(peerUserId: Long, text: String)
}

/** Connection state, in the order a login actually goes through it. */
enum class ImConnection {
    /** Not initialized, logged out, or the socket dropped without a login to retry. */
    OFFLINE,

    /** [ImClient.login] started; the SDK is connecting. */
    CONNECTING,

    /** The SDK answered that the session is up. */
    CONNECTED,
}

/**
 * A friend mutation OpenIM pushed. [kind] uses the recovered `CmdFriendBean` vocabulary so the cache
 * writer maps this one-to-one: `ADD = 1`, `DELETE = 2`, `UPDATE = 3` (`bean/CmdFriendBean.kt`).
 */
data class ImFriendEvent(val kind: Int, val userId: Long, val remark: String? = null) {
    companion object {
        const val ADD = 1
        const val DELETE = 2
        const val UPDATE = 3
    }
}

/** A message in the neutral model: who it is with, what was said, which side said it. */
data class ImMessage(
    val peerUserId: Long,
    val text: String,
    val incoming: Boolean,
    val epochSeconds: Long,
    /** The SDK's own message id, so the chat screen can key rows and de-duplicate sends. */
    val clientId: String? = null,
)

/** One conversation row: the peer, the last line, the badge. */
data class ImConversation(
    val peerUserId: Long,
    val lastText: String?,
    val unread: Int,
    val updatedAtSeconds: Long,
)
