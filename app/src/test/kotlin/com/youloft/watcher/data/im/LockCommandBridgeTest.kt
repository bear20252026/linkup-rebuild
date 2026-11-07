package com.youloft.watcher.data.im

import com.youloft.linkup.api.dto.QuestionBean
import com.youloft.linkup.im.CmdType
import com.youloft.linkup.im.ImClient
import com.youloft.linkup.im.ImCommand
import com.youloft.linkup.im.ImConnection
import com.youloft.linkup.im.ImFriendEvent
import com.youloft.linkup.im.ImMessage
import com.youloft.linkup.net.ApiResponse
import com.youloft.linkup.net.LinkUpApi
import com.youloft.watcher.bean.lock.LockScreen
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

/** 锁机命令桥：发起端"台账先行、命令随后"；收端把 lockMachine 命令转成 LockScreen。 */
class LockCommandBridgeTest {

    @Test
    fun `sendLock posts the ledger first, then the command`() = runBlocking {
        val api = fakeApi()
        val client = FakeImClient()
        val bridge = LockCommandBridge(client, api, unconfined()) {}
        val screen = LockScreen(userid = 10000002L, lockType = 2, lockTitle = "写作业", lockDuration = 600L)

        bridge.sendLock(10000002L, screen)

        assertEquals(1, apiCalls.get(), "the ledger POST goes out before the command")
        assertEquals(1, client.commandsSent.size)
        assertEquals(
            CmdType.LOCK_MACHINE,
            client.commandsSent.single().first,
            "the command rides the lockMachine action to the friend",
        )
    }

    @Test
    fun `an incoming lockMachine command becomes a LockScreen callback`() = runBlocking {
        val client = FakeImClient()
        val received = mutableListOf<LockScreen>()
        val bridge = LockCommandBridge(client, fakeApi(), unconfined(), onLock = { received += it })
        bridge.start()

        val payload = LockScreen(userid = 10000001L, lockType = 1, lockTitle = "吃饭啦")
        client.emit(
            ImCommand(peerUserId = 10000001L, action = CmdType.LOCK_MACHINE, data = payload.toJson(), incoming = true),
        )

        assertEquals(listOf(payload), received)
    }

    @Test
    fun `the remote-unlock variant does not re-lock the screen`() = runBlocking {
        val client = FakeImClient()
        val received = mutableListOf<LockScreen>()
        val bridge = LockCommandBridge(client, fakeApi(), unconfined(), onLock = { received += it })
        bridge.start()

        val unlock = LockScreen(userid = 10000001L, lockType = LockCommandBridge.LOCK_TYPE_REMOTE_UNLOCK)
        client.emit(ImCommand(10000001L, CmdType.LOCK_MACHINE, unlock.toJson(), incoming = true))

        assertTrue(received.isEmpty(), "lockType 3 is the unlock receipt, not a lock")
    }

    // ------------------------------------------------------------------ fixtures

    private fun unconfined() = CoroutineScope(Dispatchers.Unconfined + Job())

    private val apiCalls = java.util.concurrent.atomic.AtomicInteger()

    private fun fakeApi(): LinkUpApi = Proxy.newProxyInstance(
        LinkUpApi::class.java.classLoader,
        arrayOf(LinkUpApi::class.java),
    ) { _, method, _ ->
        if (method.name == "relationLock" || method.name == "relationUnlock") {
            apiCalls.incrementAndGet()
            ApiResponse<Unit>(200, null, null, null)
        } else {
            error("unexpected call: " + method.name)
        }
    } as LinkUpApi

    private class FakeImClient : ImClient {
        override val connectionState = MutableStateFlow(ImConnection.CONNECTED)
        override val friendEvents = MutableSharedFlow<ImFriendEvent>(extraBufferCapacity = 8)
        override val messages = MutableSharedFlow<ImMessage>(extraBufferCapacity = 8)
        override val commands = MutableSharedFlow<ImCommand>(extraBufferCapacity = 8)

        val commandsSent = mutableListOf<Pair<String, Map<String, Any?>>>()

        override suspend fun login(imUserId: String, token: String, wsUrl: String) = Unit
        override suspend fun logout() = Unit
        override suspend fun conversations() = emptyList<com.youloft.linkup.im.ImConversation>()
        override suspend fun history(peerUserId: Long, count: Int) = emptyList<ImMessage>()
        override suspend fun sendText(peerUserId: Long, text: String) = Unit

        override suspend fun sendCommand(peerUserId: Long, action: String, data: String) {
            commandsSent += action to mapOf("peerUserId" to peerUserId.toString(), "data" to data)
        }

        suspend fun emit(command: ImCommand) {
            commands.emit(command)
        }
    }
}
