package com.youloft.watcher.data.im

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.youloft.linkup.im.CmdType
import com.youloft.linkup.im.ImClient
import com.youloft.linkup.im.ImCommand
import com.youloft.linkup.im.ImConnection
import com.youloft.linkup.im.ImConversation
import com.youloft.linkup.im.ImFriendEvent
import com.youloft.linkup.im.ImMessage
import com.youloft.linkup.net.ApiResponse
import com.youloft.linkup.net.LinkUpApi
import com.youloft.watcher.bean.CmdScreenShot
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 截屏命令桥的发送方守门（审计 P0-3）：非好友的采集请求丢弃（不回执、不触发无障碍），
 * 确认好友的请求进状态机。回执断言走 [ImClient.sendCommand] 的记录——采集端给发起方的
 * step1/2/3 就是从这里发出去的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.youloft.watcher.pages.friend.FriendFlowFixtures.TestApplication::class)
class ScreenshotCommandBridgeTest {

    @Test
    fun `a request from a non-friend is dropped without a reply`() = runBlocking {
        val client = FakeImClient()
        val bridge = bridge(client, trusted = { false })
        bridge.start()

        client.emit(request(from = STRANGER))

        assertTrue(client.commandsSent.isEmpty(), "a non-friend must not get a step receipt")
    }

    @Test
    fun `a request from a confirmed friend reaches the availability step`() = runBlocking {
        val client = FakeImClient()
        val bridge = bridge(client, trusted = { true })
        bridge.start()

        client.emit(request(from = FRIEND))

        // 无障碍服务未连（JVM 上 instance 为空），step1 照发：请求进了状态机，只是如实回答"不可用"。
        assertEquals(1, client.commandsSent.size)
        val (address, data) = client.commandsSent.single()
        assertEquals(FRIEND.toString(), address["peerUserId"])
        val reply = CmdScreenShot.fromJson(data["data"] as String)
        assertTrue(reply != null && reply.step == 1 && !reply.accessibilityAvailable)
    }

    // ------------------------------------------------------------------ fixtures

    private val FRIEND = 10000001L
    private val STRANGER = 99999999L

    private fun bridge(client: ImClient, trusted: suspend (Long) -> Boolean) = ScreenshotCommandBridge(
        context = ApplicationProvider.getApplicationContext<Context>(),
        imClient = client,
        api = fakeApi(),
        scope = CoroutineScope(Dispatchers.Unconfined + Job()),
        trusted = trusted,
        myUserId = { 10000000L },
    )

    private fun request(from: Long): ImCommand {
        // callBack=0 是发起请求（桥内私有常量 CALLBACK_REQUEST）；callBack=1 的回执走另一条流。
        val payload = CmdScreenShot(userid = from, step = 1, callBack = 0)
        return ImCommand(peerUserId = from, action = CmdType.SCREEN_SHOT, data = payload.toJson(), incoming = true)
    }

    private fun fakeApi(): LinkUpApi = Proxy.newProxyInstance(
        LinkUpApi::class.java.classLoader,
        arrayOf(LinkUpApi::class.java),
    ) { _, method, _ -> error("unexpected call: " + method.name) } as LinkUpApi

    private class FakeImClient : ImClient {
        override val connectionState = MutableStateFlow(ImConnection.CONNECTED)
        override val friendEvents = MutableSharedFlow<ImFriendEvent>(extraBufferCapacity = 8)
        override val messages = MutableSharedFlow<ImMessage>(extraBufferCapacity = 8)
        override val commands = MutableSharedFlow<ImCommand>(extraBufferCapacity = 8)

        val commandsSent = mutableListOf<Pair<Map<String, Any?>, Map<String, Any?>>>()

        override suspend fun login(imUserId: String, token: String, wsUrl: String) = Unit
        override suspend fun logout() = Unit
        override suspend fun conversations() = emptyList<ImConversation>()
        override suspend fun history(peerUserId: Long, count: Int) = emptyList<ImMessage>()
        override suspend fun sendText(peerUserId: Long, text: String) = Unit
        override suspend fun sendImage(peerUserId: Long, localPath: String): String? = null

        override suspend fun sendCommand(peerUserId: Long, action: String, data: String) {
            commandsSent += mapOf("peerUserId" to peerUserId.toString(), "action" to action) to
                mapOf("peerUserId" to peerUserId.toString(), "data" to data)
        }

        suspend fun emit(command: ImCommand) {
            commands.emit(command)
        }
    }
}
