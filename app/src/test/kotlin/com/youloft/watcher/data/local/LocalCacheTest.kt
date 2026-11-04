package com.youloft.watcher.data.local

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.youloft.linkup.net.ConfigService
import com.youloft.watcher.data.LinkUpApplication
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the recovered storage facts: which key lives in which MMKV instance, the VIP rule, the friend
 * count, and the channel-gate rule. The in-memory double keeps this off the native library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LocalCacheTest.InjectedApp::class)
class LocalCacheTest {

    class InjectedApp : LinkUpApplication() {
        var stores = InMemoryKeyValueStores()
        var channel: InstallChannel = FallbackInstallChannel("3801")
        var config: RemoteConfig = FakeRemoteConfig()

        override val keyValueStores: KeyValueStores get() = stores
        override val installChannel: InstallChannel get() = channel
        override val remoteConfig: RemoteConfig get() = config
    }

    class FakeRemoteConfig : RemoteConfig {
        var entries: List<String> = emptyList()
        override fun stringList(key: String): List<String> = entries
    }

    private fun app(shared: Map<String, String> = emptyMap()) =
        InjectedApp().apply { stores = InMemoryKeyValueStores(shared) }

    private fun userJson(userId: Long = 42L, vipSubType: Int = 0, sharedVip: Int? = null) =
        buildString {
            append("""{"userId":""").append(userId)
            append(""","vipSubType":""").append(vipSubType)
            if (sharedVip != null) {
                append(""","shareToMeList":[{"vipSubType":""").append(sharedVip).append("}]")
            }
            append('}')
        }

    @Test
    fun `the recovered key names are the ones we read`() {
        assertEquals("user2", UserCache.KEY_USER)
        assertEquals("friendList", FriendCache.KEY_FRIENDS)
        assertEquals("showPermissionChannel", PermissionChannelGate.KEY)
    }

    @Test
    fun `no cached account reads as a plain non-vip user`() {
        val cache = UserCache(InMemoryKeyValueStores())

        assertEquals(0L, cache.userId())
        assertFalse(cache.isVip())
    }

    @Test
    fun `a malformed cache record falls back the same way instead of throwing`() {
        val cache = UserCache(InMemoryKeyValueStores(mapOf(UserCache.KEY_USER to "{not json")))

        assertEquals(0L, cache.userId())
        assertFalse(cache.isVip())
    }

    @Test
    fun `vip comes from the own subscription or from any shared grant`() {
        assertTrue(UserCache(InMemoryKeyValueStores(mapOf(UserCache.KEY_USER to userJson(vipSubType = 2)))).isVip())
        assertTrue(
            UserCache(InMemoryKeyValueStores(mapOf(UserCache.KEY_USER to userJson(vipSubType = 0, sharedVip = 1)))).isVip(),
        )
        assertFalse(
            UserCache(InMemoryKeyValueStores(mapOf(UserCache.KEY_USER to userJson(vipSubType = 0, sharedVip = 0)))).isVip(),
        )
    }

    @Test
    fun `the friend list is read from the store named after the logged-in user`() {
        val stores = InMemoryKeyValueStores(mapOf(UserCache.KEY_USER to userJson(userId = 42L)))
        // A decoy in the shared store must not be what the cache reads.
        stores.putForUser(7L, FriendCache.KEY_FRIENDS, """[{"userId":7}]""")
        stores.putForUser(42L, FriendCache.KEY_FRIENDS, """[{"userId":1},{"userId":2},null]""")

        val cache = FriendCache(stores, UserCache(stores))

        assertEquals(listOf(1L, 2L), cache.cachedFriends().map { it.userId })
        assertEquals(2, cache.cachedCount())
    }

    @Test
    fun `a missing friend list counts as zero, like the original`() {
        val stores = InMemoryKeyValueStores(mapOf(UserCache.KEY_USER to userJson(userId = 42L)))

        assertEquals(0, FriendCache(stores, UserCache(stores)).cachedCount())
    }

    @Test
    fun `the gate has one rule per shipped version`() {
        fun gate(entries: List<String>, channel: String) = PermissionChannelGate(
            FallbackInstallChannel(channel),
            FakeRemoteConfig().apply { this.entries = entries },
        )

        // 1.6.6 replaced the remote list with a single channel comparison.
        assertTrue(gate(emptyList(), "3301").asksAfterAddFriend())
        assertFalse(gate(listOf("3301"), "3302").asksAfterAddFriend())

        // 1.5.6 read the list and honoured the -1 wildcard.
        assertTrue(gate(listOf("3801", "3300"), "3801").asksAfterAddFriendByConfig())
        assertTrue(gate(listOf("-1"), "whatever").asksAfterAddFriendByConfig(), "the recovered wildcard was -1")
        assertFalse(gate(listOf("3300"), "3801").asksAfterAddFriendByConfig())
        assertFalse(gate(emptyList(), "3801").asksAfterAddFriendByConfig())
    }

    @Test
    fun `only four channels put a hint under the add-friend screen`() {
        fun tips(channel: String) = AddFriendTipsPolicy(FallbackInstallChannel(channel)).tips()

        assertEquals(AddFriendTips.LocationMutualConsent, tips("3801"))
        assertEquals(AddFriendTips.BothInstallOrVip, tips("3301"))
        assertEquals(AddFriendTips.BothInstallApp, tips("3300"))
        assertEquals(AddFriendTips.BothInstallApp, tips("3302"))
        assertNull(tips("0"), "the store builds that were never given a hint")
    }

    @Test
    fun `the application graph now reports the stored readings`() {
        val application = app(
            mapOf(
                UserCache.KEY_USER to userJson(userId = 42L, vipSubType = 3),
            ),
        ).apply {
            stores.putForUser(42L, FriendCache.KEY_FRIENDS, """[{"userId":1},{"userId":2},{"userId":3}]""")
            channel = FallbackInstallChannel("3301")
        }

        assertTrue(application.isVip)
        assertEquals(3, application.cachedFriendCount)
        assertTrue(application.askPermissionsAfterAdd)
        assertEquals(AddFriendTips.BothInstallOrVip, application.addFriendTips)
    }

    @Test
    fun `the config writer stores the three bookkeeping keys the original used`() {
        val stores = InMemoryKeyValueStores()

        stores.writeConfigFetch(parameterFetch("""{"showPermissionChannel":["3300","3301","-1"]}"""), "166", 5_000L)

        assertEquals("17533440860101", stores.shared.string(StoredRemoteConfig.KEY_LAST_UPDATE))
        assertEquals("166", stores.shared.string(StoredRemoteConfig.KEY_LAST_REQUEST_VER))
        assertEquals("5000", stores.shared.string(StoredRemoteConfig.KEY_LAST_REQUEST_AT))
    }

    @Test
    fun `a nothing-changed answer leaves the stored parameters alone`() {
        val stores = InMemoryKeyValueStores()
        stores.writeConfigFetch(parameterFetch("""{"showPermissionChannel":["3300"]}"""), "166", 1_000L)

        // `of.b.o` on an empty msg: only the request time moves.
        stores.writeConfigFetch(ConfigService.Fetch(emptyMap(), JsonObject(), ""), "166", 2_000L)

        assertEquals(
            listOf("3300"),
            StoredRemoteConfig(stores).stringList(PermissionChannelGate.KEY),
            "the old Parameter_Map must survive a no-change reply",
        )
        assertEquals("2000", stores.shared.string(StoredRemoteConfig.KEY_LAST_REQUEST_AT))
        assertEquals("17533440860101", stores.shared.string(StoredRemoteConfig.KEY_LAST_UPDATE), "an empty token does not erase it")
    }

    @Test
    fun `the stored parameters are what the gate reads, and 3305 splits on the two rules`() {
        val stores = InMemoryKeyValueStores()
        stores.writeConfigFetch(parameterFetch("""{"showPermissionChannel":["3300","3301","-1"]}"""), "166", 1_000L)
        val gate = PermissionChannelGate(FallbackInstallChannel("3305"), StoredRemoteConfig(stores))

        // Measured facts: shipped APKs carry channel 3305, the live list carries the -1 wildcard.
        assertTrue(gate.asksAfterAddFriendByConfig(), "the 1.5.6 rule asked, through the wildcard")
        assertFalse(gate.asksAfterAddFriend(), "the 1.6.6 rule stopped asking for every build but 3301")
    }

    @Test
    fun `an empty install keeps the defaults off`() {
        val application = app()

        assertFalse(application.isVip)
        assertEquals(0, application.cachedFriendCount)
        assertFalse(application.askPermissionsAfterAdd)
    }

    /** A fetch in the shape [com.youloft.linkup.net.ConfigService] hands back after base64-decoding `msg`. */
    private fun parameterFetch(json: String): ConfigService.Fetch = ConfigService.Fetch(
        values = mapOf(StoredRemoteConfig.KEY_PARAMETER_MAP to json),
        parameterMap = JsonParser.parseString(json).asJsonObject,
        lastUpdate = "17533440860101",
    )
}
