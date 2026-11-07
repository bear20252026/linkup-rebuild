package com.youloft.watcher.pages.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.youloft.linkup.im.ImClient
import com.youloft.linkup.im.ImMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The chat screen's state: one peer, the rows on screen, the send in flight.
 *
 * Rows are [ImMessage]s verbatim — the neutral model the `:im` interface hands over — plus the optimistic
 * append after a send the transport accepted, so what the user typed appears the moment the SDK says it
 * was queued rather than waiting for the socket to echo it back. An incoming message that duplicates an
 * optimistic row (same client id) replaces it instead of doubling.
 */
class ChatViewModel(private val imClient: ImClient, private val peerUserId: Long) : ViewModel() {

    data class ChatUiState(
        val rows: List<ImMessage> = emptyList(),
        val sending: Boolean = false,
        val connection: com.youloft.linkup.im.ImConnection = com.youloft.linkup.im.ImConnection.OFFLINE,
        val error: String? = null,
    )

    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState

    private var historyJob: Job? = null

    /** Subscribes the peer's stream and loads recent history; call once from the screen. */
    fun start() {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            val history = runCatching { imClient.history(peerUserId, HISTORY_COUNT) }.getOrNull().orEmpty()
            mutableState.update { it.copy(rows = history) }
        }
        viewModelScope.launch {
            imClient.messages.collect { message ->
                if (message.peerUserId != peerUserId) return@collect
                mutableState.update { state ->
                    val withoutOptimistic = state.rows.filterNot {
                        !it.incoming && it.clientId != null && it.clientId == message.clientId
                    }
                    state.copy(rows = withoutOptimistic + message)
                }
            }
        }
        viewModelScope.launch {
            imClient.connectionState.collect { connection ->
                mutableState.update { it.copy(connection = connection) }
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        mutableState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                imClient.sendText(peerUserId, text.trim())
                mutableState.update { state ->
                    // Optimistic row: no client id yet, so the socket echo cannot be de-duplicated —
                    // a later push with one replaces nothing and shows beside it, which is the honest
                    // answer until the transport round-trips ids end to end (on-device item).
                    state.copy(
                        sending = false,
                        rows = state.rows + ImMessage(
                            peerUserId = peerUserId,
                            text = text.trim(),
                            incoming = false,
                            epochSeconds = System.currentTimeMillis() / 1000,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(sending = false, error = "发送失败：${error.message}") }
            }
        }
    }

    /** 图片：transport 负责上传（OpenIMServer→MinIO），成功后本地不插行——回执流会带来同 id 消息。 */
    fun sendImage(localPath: String) {
        mutableState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                imClient.sendImage(peerUserId, localPath)
                mutableState.update { it.copy(sending = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(sending = false, error = "发送失败：${error.message}") }
            }
        }
    }

    fun consumeError() {
        mutableState.update { it.copy(error = null) }
    }

    companion object {
        fun factory(imClient: ImClient, peerUserId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(imClient, peerUserId) as T
        }

        const val HISTORY_COUNT = 20
    }
}
