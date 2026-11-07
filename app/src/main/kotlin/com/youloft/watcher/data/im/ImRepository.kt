package com.youloft.watcher.data.im

import com.youloft.linkup.im.ImClient
import com.youloft.linkup.net.LinkUpApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The session half of the IM card: turn a signed-in LinkUp account into an OpenIM session, and nothing
 * more. Every input the SDK needs travels in one response — `api/user/im_token` answers the identity (the
 * signed-in account's decimal form), its issued token, and the msg_gateway address — so this class
 * synthesizes nothing: no fallback url, no locally minted token, no retry loop. A failed connect is a log
 * line and an OFFLINE client, and the next `onSignedIn` tries again.
 *
 * Testable against a fake [ImClient] and a proxied [LinkUpApi], because the transport itself (native
 * library, socket) is the on-device verification item.
 */
class ImRepository(
    private val api: LinkUpApi,
    private val imClient: ImClient,
    private val scope: CoroutineScope,
    private val onEvent: (String) -> Unit = {},
) {

    /** Last connect outcome, for the probe screen and tests; null means no attempt this process. */
    var lastError: String? = null
        private set

    /** Fire-and-forget from the account side effects; never throws into its caller. */
    fun connectAsync(userId: Long) {
        if (userId <= 0) return
        scope.launch { connect(userId) }
    }

    suspend fun connect(userId: Long) {
        if (userId <= 0) return
        val response = runCatching { api.imToken() }
            .onFailure { record("im_token 请求失败: $it") }
            .getOrNull() ?: return
        val data = response.data
        if (!response.isSuccess || data == null) {
            record("im_token 被拒: status=${response.status} msg=${response.msg}")
            return
        }
        if (data.wsUrl.isBlank() || data.imToken.isBlank()) {
            record("im_token 回包不完整: wsUrl=${data.wsUrl} token=${data.imToken.isNotEmpty()}")
            return
        }
        runCatching { imClient.login(data.imUserId, data.imToken, data.wsUrl) }
            .onSuccess { record("IM 连接已发起 imUserId=${data.imUserId}") }
            .onFailure { record("IM 登录失败: $it") }
    }

    suspend fun disconnect() {
        runCatching { imClient.logout() }
            .onSuccess { record("IM 已断开") }
            .onFailure { record("IM 断开失败: $it") }
    }

    private fun record(message: String) {
        lastError = message.takeIf { it.contains("失败") || it.contains("被拒") || it.contains("不完整") }
        onEvent(message)
    }
}
