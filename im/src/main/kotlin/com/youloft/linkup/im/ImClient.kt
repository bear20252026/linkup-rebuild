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
     * 设备间命令流（原版 `widget/im/CmdMsgManager` 的 CMD 消息）：锁机（`lockMachine`）、截屏
     * （`screenShot`，166 新增）等。`action` 走 OpenIM custom message 的 extension，`data` 是原版
     * 语义里的 JSON（`LockScreen` 等），两边都与反编译字典逐字对应。
     */
    val commands: SharedFlow<ImCommand>

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

    /**
     * Sends one image from a local file path. The transport uploads the file (OpenIMServer's own
     * object store) as part of the send, then the ordinary message flow delivers it. The answer is
     * the served URL once the SDK filled it in — the screenshot card's step-3 receipt carries it
     * (null when the transport could not produce one).
     */
    suspend fun sendImage(peerUserId: Long, localPath: String): String?

    /**
     * Sends one device command as an OpenIM custom message — the port of the original's
     * `EMMessage.createSendMessage(Type.CMD)` + `setAttribute("data", gson(...))`, with the cmdType
     * action carried in the custom message's extension field.
     */
    suspend fun sendCommand(peerUserId: Long, action: String, data: String)
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
    /**
     * The served media URL (image/voice), straight from the SDK's element — the transport uploads
     * through OpenIMServer's own object store (MinIO in the rebuilt stack), so the URL is stable
     * across clients. Null for text.
     */
    val mediaUrl: String? = null,
) {
    /** 文本之外的本轮媒体种类；语音/视频走各自卡时在此扩展。 */
    val isImage: Boolean get() = mediaUrl != null
}

/** One conversation row: the peer, the last line, the badge. */
data class ImConversation(
    val peerUserId: Long,
    val lastText: String?,
    val unread: Int,
    val updatedAtSeconds: Long,
)

/** 设备间命令：`action` 是原版 `CmdType` 的 action 字符串，`data` 是随行的 JSON。 */
data class ImCommand(val peerUserId: Long, val action: String, val data: String, val incoming: Boolean)

/**
 * The recovered `CmdType` action strings (`annotation/CmdType.java`, 156; 166 adds `screenShot`).
 * 传输层只认识 action 串本身——语义在各功能卡。
 */
object CmdType {
    const val LOCK_MACHINE = "lockMachine"
    const val SCREEN_SHOT = "screenShot"
    const val UPDATE_LOCATION = "update_location"
    const val APP_USAGE = "app_usage"
    const val UPDATE_PERMISSIONS = "update_permissions"
    const val PING = "ping"
}
