package com.youloft.linkup.im

import android.app.Application
import com.google.gson.Gson
import io.openim.android.sdk.OpenIMClient
import io.openim.android.sdk.enums.ConversationType
import io.openim.android.sdk.enums.ViewType
import io.openim.android.sdk.listener.OnAdvanceMsgListener
import io.openim.android.sdk.listener.OnBase
import io.openim.android.sdk.listener.OnConnListener
import io.openim.android.sdk.listener.OnFriendshipListener
import io.openim.android.sdk.models.FriendInfo
import io.openim.android.sdk.models.InitConfig
import io.openim.android.sdk.models.Message
import io.openim.android.sdk.models.OfflinePushInfo
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The OpenIM transport — the one place in the rebuild that names the vendor's client SDK as a compile
 * dependency, by decision: the rebuilt gateway fronts OpenIMServer, and this is its official client.
 *
 * Everything structural lives in [ImClient]/[ImMappings]; this file is the glue, and the glue is the
 * part the JVM cannot run — the SDK loads a native library on first init, so every call below is
 * verified on-device only (the verification item this card hands forward). What the tests *do* pin is
 * the mapping layer and the shapes of the callbacks this glue wires.
 *
 * ## Token and address discipline
 *
 * `login` takes the `api/user/im_token` response verbatim: the IM user id (the signed-in LinkUp account's
 * decimal form), the issued token, and the msg_gateway address. Nothing here synthesizes a fallback —
 * an empty address means the gateway did not answer, and the login fails rather than dialing a guess.
 */
class OpenImImClient(
    private val app: Application,
    private val gson: Gson = defaultGson(),
) : ImClient {

    private val _connectionState = MutableStateFlow(ImConnection.OFFLINE)
    override val connectionState: StateFlow<ImConnection> = _connectionState

    private val _friendEvents = MutableSharedFlow<ImFriendEvent>(extraBufferCapacity = 64)
    override val friendEvents: SharedFlow<ImFriendEvent> = _friendEvents

    private val _messages = MutableSharedFlow<ImMessage>(extraBufferCapacity = 64)
    override val messages: SharedFlow<ImMessage> = _messages

    private val _commands = MutableSharedFlow<ImCommand>(extraBufferCapacity = 64)
    override val commands: SharedFlow<ImCommand> = _commands

    /** Who the SDK session belongs to, needed to tell incoming from outgoing on every message. */
    private var loginUserId: String? = null
    private var initialized = false

    override suspend fun login(imUserId: String, token: String, wsUrl: String) {
        ensureInitialized(wsUrl)
        _connectionState.value = ImConnection.CONNECTING
        loginUserId = imUserId
        awaitBase { callback -> OpenIMClient.getInstance().login(callback, imUserId, token) }
        // The conn listener flips the state on connect; the login callback only means the credential
        // was accepted, so nothing is set here — OFFLINE stays honest until the socket answers.
    }

    override suspend fun logout() {
        runCatching { awaitBase { callback -> OpenIMClient.getInstance().logout(callback) } }
        loginUserId = null
        _connectionState.value = ImConnection.OFFLINE
    }

    override suspend fun conversations(): List<ImConversation> {
        val list = awaitBase<List<io.openim.android.sdk.models.ConversationInfo>> { callback ->
            OpenIMClient.getInstance().conversationManager.getAllConversationList(callback)
        }
        return list.orEmpty().map(ImMappings::toImConversation)
    }

    override suspend fun history(peerUserId: Long, count: Int): List<ImMessage> {
        val me = loginUserId ?: return emptyList()
        val conversation = awaitBase<io.openim.android.sdk.models.ConversationInfo> { callback ->
            OpenIMClient.getInstance().conversationManager.getOneConversation(
                callback, peerUserId.toString(), ConversationType.SINGLE_CHAT,
            )
        } ?: return emptyList()
        val advanced = awaitBase<io.openim.android.sdk.models.AdvancedMessage> { callback ->
            OpenIMClient.getInstance().messageManager.getAdvancedHistoryMessageList(
                callback, conversation.conversationID, null, count, ViewType.History,
            )
        } ?: return emptyList()
        // The SDK answers newest first; the interface promises oldest first, so the walk reverses.
        return advanced.messageList.orEmpty()
            .map { ImMappings.toImMessage(it, me) }
            .sortedBy { it.epochSeconds }
    }

    override suspend fun sendText(peerUserId: Long, text: String) {
        val message: Message = OpenIMClient.getInstance().messageManager.createTextMessage(text)
        suspendCancellableCoroutine { continuation ->
            // sendMessage demands OnMsgSendCallback (OnBase<Message> plus a progress hook), not OnBase.
            OpenIMClient.getInstance().messageManager.sendMessage(
                object : io.openim.android.sdk.listener.OnMsgSendCallback {
                    override fun onSuccess(data: Message) {
                        if (continuation.isActive) continuation.resume(data)
                    }

                    override fun onError(code: Int, error: String) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(ImTransportException(code, error)))
                        }
                    }
                },
                message,
                peerUserId.toString(),
                /* groupID = */ "",
                OfflinePushInfo(),
            )
        }
    }

    override suspend fun sendCommand(peerUserId: Long, action: String, data: String) {
        val message: Message = OpenIMClient.getInstance().messageManager.createCustomMessage(
            /* data = */ data,
            /* extension = */ action,
            /* description = */ "",
        )
        suspendCancellableCoroutine { continuation ->
            // sendMessage demands OnMsgSendCallback, same as the text path.
            OpenIMClient.getInstance().messageManager.sendMessage(
                object : io.openim.android.sdk.listener.OnMsgSendCallback {
                    override fun onSuccess(data: Message) {
                        if (continuation.isActive) continuation.resume(data)
                    }

                    override fun onError(code: Int, error: String) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(ImTransportException(code, error)))
                        }
                    }
                },
                message,
                peerUserId.toString(),
                /* groupID = */ "",
                OfflinePushInfo(),
            )
        }
    }

    // ------------------------------------------------------------------ internals

    /** initSDK once per process; the ws address travels in the same response the token came from. */
    private fun ensureInitialized(wsUrl: String) {
        if (initialized) return
        val config = InitConfig(
            /* apiAddr = */ "",
            /* wsAddr = */ wsUrl,
            /* dataDir = */ app.applicationContext.filesDir.resolve("openim").absolutePath,
        )
        OpenIMClient.getInstance().initSDK(app, config, connListener)
        // Listeners attach only once the SDK is up: the managers' setters reach into the Go bindings,
        // whose static initialization loads the native library — touching them on a JVM (any unit test
        // that merely builds the graph) would UnsatisfiedLink before login is even attempted.
        OpenIMClient.getInstance().friendshipManager.setOnFriendshipListener(friendshipListener)
        OpenIMClient.getInstance().messageManager.setAdvancedMsgListener(messageListener)
        initialized = true
    }

    private val connListener = object : OnConnListener {
        override fun onConnecting() {
            _connectionState.value = ImConnection.CONNECTING
        }

        override fun onConnectSuccess() {
            _connectionState.value = ImConnection.CONNECTED
        }

        override fun onConnectFailed(code: Int, error: String) {
            _connectionState.value = ImConnection.OFFLINE
        }

        override fun onKickedOffline() {
            _connectionState.value = ImConnection.OFFLINE
        }

        override fun onUserTokenExpired() {
            _connectionState.value = ImConnection.OFFLINE
        }

        override fun onUserTokenInvalid(error: String) {
            _connectionState.value = ImConnection.OFFLINE
        }
    }

    private val friendshipListener = object : OnFriendshipListener {
        override fun onFriendAdded(info: FriendInfo) {
            _friendEvents.tryEmit(ImMappings.toImFriendEvent(info, ImFriendEvent.ADD))
        }

        override fun onFriendDeleted(info: FriendInfo) {
            _friendEvents.tryEmit(ImMappings.toImFriendEvent(info, ImFriendEvent.DELETE))
        }

        override fun onFriendInfoChanged(info: FriendInfo) {
            _friendEvents.tryEmit(ImMappings.toImFriendEvent(info, ImFriendEvent.UPDATE))
        }
    }

    private val messageListener = object : OnAdvanceMsgListener {
        override fun onRecvNewMessage(message: Message) {
            val me = loginUserId ?: return
            // Custom messages are the device-command channel, not chat rows: a payload with an
            // extension (the original's cmdType) routes to [commands] and never to the chat list.
            val custom = message.customElem
            if (custom != null && !custom.extension.isNullOrBlank()) {
                _commands.tryEmit(
                    ImCommand(
                        peerUserId = (message.sendID).toLongOrNull() ?: 0L,
                        action = custom.extension,
                        data = custom.data.orEmpty(),
                        incoming = true,
                    ),
                )
                return
            }
            _messages.tryEmit(ImMappings.toImMessage(message, me))
        }
    }

    /** Wraps the SDK's callback pair into one suspension point; the error becomes the exception. */
    private suspend fun <T> awaitBase(call: (OnBase<T>) -> Unit): T =
        suspendCancellableCoroutine { continuation ->
            call(object : OnBase<T> {
                override fun onError(code: Int, error: String) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(ImTransportException(code, error)))
                    }
                }

                override fun onSuccess(data: T) {
                    if (continuation.isActive) continuation.resume(data)
                }
            })
        }

}

/** The SDK's own error pair, kept whole — the code is what an on-call greps. */
class ImTransportException(val code: Int, message: String) : RuntimeException("[$code] $message")
