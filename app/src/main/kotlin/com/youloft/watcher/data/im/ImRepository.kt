package com.youloft.watcher.data.im

import com.youloft.linkup.im.ImClient
import com.youloft.linkup.net.LinkUpApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        // OpenIM SDK 的 initSDK/login 内部创建 Handler，必须发生在主线程——在
        // Dispatchers.Default 直接调会 RuntimeException("Can't create handler ... Looper.prepare()")
        // 把整个进程带崩（新用户注册后首次连 IM 即触发）。整段再套 runCatching，
        // 任何 SDK 异常都降级为日志，绝不杀进程。
        runCatching {
            withContext(Dispatchers.Main.immediate) {
                imClient.login(data.imUserId, data.imToken, data.wsUrl)
            }
        }.onSuccess { record("IM 连接已发起 imUserId=${data.imUserId}") }
            .onFailure { record("IM 登录失败: $it") }
    }

    suspend fun disconnect() {
        runCatching {
            withContext(Dispatchers.Main.immediate) { imClient.logout() }
        }.onSuccess { record("IM 已断开") }
            .onFailure { record("IM 断开失败: $it") }
    }

    private fun record(message: String) {
        lastError = message.takeIf { it.contains("失败") || it.contains("被拒") || it.contains("不完整") }
        onEvent(message)
    }
}
