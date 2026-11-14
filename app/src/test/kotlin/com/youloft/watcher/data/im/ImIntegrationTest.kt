package com.youloft.watcher.data.im

import com.google.gson.Gson
import com.youloft.linkup.api.dto.ImToken
import com.youloft.linkup.api.dto.FriendList
import com.youloft.linkup.im.ImClient
import com.youloft.linkup.im.ImConnection
import com.youloft.linkup.im.ImFriendEvent
import com.youloft.linkup.im.ImMessage
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.youloft.linkup.net.ApiResponse
import com.youloft.linkup.net.LinkUpApi
import com.youloft.watcher.bean.CmdFriendBean
import com.youloft.watcher.data.FriendEvents
import com.youloft.watcher.data.local.FriendCache
import com.youloft.watcher.data.local.InMemoryKeyValueStores
import com.youloft.watcher.data.local.UserCache
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.youloft.watcher.StorelessApp

/**
 * The IM session and cache-write paths, against a proxied api and a fake transport — the same split the
 * friend-flow fixtures use. What is pinned: `im_token`'s answer reaches the SDK login verbatim, failures
 * degrade to logs instead of crashes, and an IM mutation really rewrites the friend cache (the writer the
 * rebuild was missing, recovered 待验 9).
 *
 * The source publishes through LiveData, which posts across threads — so these are Robolectric tests
 * with the main looper idled after each emission, or the assertion would race a queue nobody drains.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = StorelessApp::class)
class ImIntegrationTest {

    // ------------------------------------------------------------------ ImRepository

    @Test
    fun `the im_token answer reaches the SDK login verbatim`() = runBlocking {
        val api = fakeApi(status = 200, token = tokenPayload())
        val client = FakeImClient()
        val repo = ImRepository(api, client, unconfined())

        repo.connect(10000001)

        assertEquals(
            listOf(ImLoginCall("10000001", "im-token-1", "ws://10.0.2.2:10001")),
            client.logins,
            "identity, token and ws address pass through untouched — nothing synthesized here",
        )
        assertNull(repo.lastError)
    }

    @Test
    fun `a rejected or broken im_token means no login attempt`() = runBlocking {
        val refusedClient = FakeImClient()
        val refused = ImRepository(fakeApi(status = 401), refusedClient, unconfined())
        refused.connect(10000001)
        assertEquals(emptyList(), refusedClient.logins)

        val brokenClient = FakeImClient()
        val broken = ImRepository(throwingApi(), brokenClient, unconfined())
        broken.connect(10000001)
        assertEquals(emptyList(), brokenClient.logins, "transport failure is a log line, not a crash")
        assertTrue(broken.lastError?.contains("失败") == true)
    }

    @Test
    fun `zero userId never reaches the api`() = runBlocking {
        val client = FakeImClient()
        ImRepository(fakeApi(status = 200, token = tokenPayload()), client, unconfined()).connectAsync(0)
        assertEquals(emptyList(), client.logins, "an unsigned-out account has no session to open")
        assertNull(ImRepository(fakeApi(status = 200, token = tokenPayload()), client, unconfined()).lastError)
    }

    @Test
    fun `disconnect logs the session out`() = runBlocking {
        val client = FakeImClient()
        ImRepository(fakeApi(status = 200, token = tokenPayload()), client, unconfined()).disconnect()
        assertEquals(1, client.logouts)
    }

    // ------------------------------------------------------------------ ImFriendListSource

    @Test
    fun `an IM mutation rewrites the cache and republishes the list`() = runBlocking {
        val stores = InMemoryKeyValueStores()
        val users = UserCache(stores)
        val source = ImFriendListSource(
            refresh = { ApiResponse(200, null, null, FriendList(list = listOf(friend(10000002, "女儿")))) },
            storesProvider = { stores },
            usersProvider = { users },
            imClientProvider = { fakeWithEvents },
            scope = unconfined(),
        )
        source.start()

        fakeWithEvents.emitFriend(ImFriendEvent(ImFriendEvent.ADD, 10000002, "女儿"))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(10000002L), source.friends.value?.map { info -> info.id }, "the server answer is the list")
        // The write the rebuild was missing: the same key FriendCache reads, now holding the refresh.
        val cached = FriendCache(stores, users).cachedFriends()
        assertEquals(listOf(10000002L), cached.map { info -> info.id })
        assertEquals(
            listOf(CmdFriendBean(type = CmdFriendBean.ADD, userid = 10000002L)),
            listOfNotNull(source.friendCommand.value),
        )
    }

    @Test
    fun `the friend_delete broadcast lands in the same refresh`() = runBlocking {
        val stores = InMemoryKeyValueStores()
        val users = UserCache(stores)
        var refreshCount = 0
        val source = ImFriendListSource(
            refresh = {
                refreshCount++
                ApiResponse(200, null, null, FriendList(list = emptyList()))
            },
            storesProvider = { stores },
            usersProvider = { users },
            imClientProvider = { fakeWithEvents },
            scope = unconfined(),
        )
        source.start()

        FriendEvents.publishFriendDeleted(10000002)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, refreshCount, "the screen-side deletion and the IM push share one write path")
        assertEquals(CmdFriendBean.DELETE, source.friendCommand.value?.type)
    }

    @Test
    fun `a failed refresh keeps the previous list on screen`() = runBlocking {
        val stores = InMemoryKeyValueStores()
        val users = UserCache(stores)
        var failing = false
        val source = ImFriendListSource(
            refresh = {
                if (failing) ApiResponse(500, "服务暂时不可用", null, null)
                else ApiResponse(200, null, null, FriendList(list = listOf(friend(10000002, "女儿"))))
            },
            storesProvider = { stores },
            usersProvider = { users },
            imClientProvider = { fakeWithEvents },
            scope = unconfined(),
        )
        source.start()
        fakeWithEvents.emitFriend(ImFriendEvent(ImFriendEvent.ADD, 10000002))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, source.friends.value?.size)

        failing = true
        fakeWithEvents.emitFriend(ImFriendEvent(ImFriendEvent.UPDATE, 10000002))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, source.friends.value?.size, "a broken refresh never blanks the list")
        assertNull(source.friendCommand.value?.takeIf { it.type == CmdFriendBean.UPDATE })
    }

    // ------------------------------------------------------------------ fixtures

    private val fakeWithEvents = FakeImClient()

    private fun unconfined() = CoroutineScope(Dispatchers.Unconfined + Job())

    private fun friend(id: Long, remark: String) = FriendList.Info(id = id, remark = remark)

    private fun tokenPayload() = ImToken(
        imUserId = "10000001",
        imToken = "im-token-1",
        expireTimeSeconds = 86_400,
        wsUrl = "ws://10.0.2.2:10001",
    )

    /** The fixtures' pattern: a Proxy over LinkUpApi answering from a map of recorded invocations. */
    private fun fakeApi(status: Int, token: ImToken? = null): LinkUpApi = Proxy.newProxyInstance(
        LinkUpApi::class.java.classLoader,
        arrayOf(LinkUpApi::class.java),
    ) { _, method, _ ->
        if (method.name == "imToken") {
            ApiResponse(status, if (status == 200) null else "登录已失效", null, token)
        } else {
            error("unexpected call: ${method.name}")
        }
    } as LinkUpApi

    private fun throwingApi(): LinkUpApi = Proxy.newProxyInstance(
        LinkUpApi::class.java.classLoader,
        arrayOf(LinkUpApi::class.java),
    ) { _, _, _ -> throw java.io.IOException("upstream down") } as LinkUpApi

    private data class ImLoginCall(val imUserId: String, val token: String, val wsUrl: String)

    private class FakeImClient : ImClient {
        override val connectionState = MutableStateFlow(ImConnection.CONNECTED)
        override val friendEvents = MutableSharedFlow<ImFriendEvent>(extraBufferCapacity = 8)
        override val messages = MutableSharedFlow<ImMessage>(extraBufferCapacity = 8)
        override val commands = MutableSharedFlow<com.youloft.linkup.im.ImCommand>(extraBufferCapacity = 8)

        val logins = mutableListOf<ImLoginCall>()
        var logouts = 0

        override suspend fun login(imUserId: String, token: String, wsUrl: String) {
            logins += ImLoginCall(imUserId, token, wsUrl)
        }

        override suspend fun logout() {
            logouts++
        }

        override suspend fun conversations(): List<com.youloft.linkup.im.ImConversation> = emptyList()

        override suspend fun history(peerUserId: Long, count: Int): List<ImMessage> = emptyList()

        override suspend fun sendText(peerUserId: Long, text: String) = Unit

        override suspend fun sendImage(peerUserId: Long, localPath: String): String? = null

        override suspend fun sendVoice(peerUserId: Long, localPath: String, durationSeconds: Long): String? = null

        override suspend fun sendCommand(peerUserId: Long, action: String, data: String) = Unit

        suspend fun emitFriend(event: ImFriendEvent) {
            friendEvents.emit(event)
        }
    }
}
