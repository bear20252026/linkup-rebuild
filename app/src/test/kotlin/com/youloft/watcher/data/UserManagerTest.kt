package com.youloft.watcher.data

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.youloft.linkup.api.dto.ShareVip
import com.youloft.linkup.api.dto.User
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.AndroidDeviceContext
import com.youloft.watcher.data.local.InMemoryKeyValueStores
import com.youloft.watcher.data.local.KeyValueStore
import com.youloft.watcher.data.local.KeyValueStores
import com.youloft.watcher.data.local.UserCache
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Covers the account write path that #16 exists for: response → MMKV → observable identity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserManagerTest {

    private class FakeUserRepository(var next: ApiResponse<User> = ApiResponse(500, "no fixture")) : UserRepository {
        var calls = 0
        override suspend fun userInfo(): ApiResponse<User> {
            calls++
            return next
        }
    }

    private class Recorder : AccountSideEffects {
        val events = mutableListOf<Pair<Long, Boolean>>()
        val signedIn = mutableListOf<Long>()
        override fun onVipStateChanged(user: User, wasVip: Boolean) {
            events += user.userId to wasVip
        }

        override fun onSignedIn(user: User) {
            signedIn += user.userId
        }

        override fun onSignedOut() {
            signedIn -= signedIn
        }
    }

    /** Records the order keys are written, which is the one thing the login write path is about. */
    private class OrderingStores : KeyValueStores {
        val writes = mutableListOf<String>()
        private val backing = InMemoryKeyValueStores()

        override val shared: KeyValueStore = recording(backing.shared)
        override fun forUser(userId: Long): KeyValueStore = recording(backing.forUser(userId))

        private fun recording(inner: KeyValueStore) = object : KeyValueStore {
            override fun string(key: String): String? = inner.string(key)
            override fun int(key: String, default: Int): Int = inner.int(key, default)
            override fun boolean(key: String, default: Boolean): Boolean = inner.boolean(key, default)
            override fun long(key: String, default: Long): Long = inner.long(key, default)
            override fun putLong(key: String, value: Long) {
                inner.putLong(key, value)
            }
            override fun putString(key: String, value: String) {
                writes += key
                inner.putString(key, value)
            }

            override fun putInt(key: String, value: Int) {
                writes += key
                inner.putInt(key, value)
            }

            override fun putBoolean(key: String, value: Boolean) {
                writes += key
                inner.putBoolean(key, value)
            }
        }
    }

    private class Fixture(Initial: Map<String, String> = emptyMap()) {
        val stores = InMemoryKeyValueStores(Initial)
        val cache = UserCache(stores)
        val repository = FakeUserRepository()
        val effects = Recorder()
        val manager = UserManager(repository, cache, effects)
    }

    private fun user(
        userId: Long = 7L,
        vipSubType: Int = 0,
        token: String? = null,
        nickname: String? = "有名字",
        isRegister: Boolean = false,
    ) = User(
        userId = userId,
        mainUser = userId,
        vipSubType = vipSubType,
        token = token,
        nickname = nickname,
        isRegister = isRegister,
    )

    @Test
    fun `the cached record is written before the token, in that order`() {
        val stores = OrderingStores()
        val manager = UserManager(FakeUserRepository(), UserCache(stores), Recorder())

        manager.store(user(token = "jwt-abc"))

        assertEquals(listOf(UserCache.KEY_USER, UserCache.KEY_TOKEN), stores.writes)
    }

    @Test
    fun `a login that still has no nickname is parked, and only the token is written`() {
        val stores = OrderingStores()
        val manager = UserManager(FakeUserRepository(), UserCache(stores), Recorder())

        manager.store(user(token = "half-login", nickname = null))

        assertEquals(listOf(UserCache.KEY_TOKEN), stores.writes, "the record must not be cached yet")
        assertEquals(0L, manager.current().userId)
        assertEquals(7L, manager.inFlight.userId, "`App.user` is where the profile screen picks it up")
    }

    @Test
    fun `a third-party login parks an account that still has to complete its profile`() {
        val stores = OrderingStores()
        val manager = UserManager(FakeUserRepository(), UserCache(stores), Recorder())

        manager.store(user(isRegister = true, token = "wx"), fromLogin = false)
        assertEquals(listOf(UserCache.KEY_TOKEN), stores.writes)
        assertEquals(7L, manager.inFlight.userId)

        val fresh = OrderingStores()
        UserManager(FakeUserRepository(), UserCache(fresh), Recorder())
            .store(user(isRegister = false, token = "wx"), fromLogin = false)
        assertEquals(listOf(UserCache.KEY_USER, UserCache.KEY_TOKEN), fresh.writes)
    }

    @Test
    fun `binding a phone replaces the record without touching the token`() {
        val stores = OrderingStores()
        val recorder = Recorder()
        val manager = UserManager(FakeUserRepository(), UserCache(stores), recorder)

        manager.saveRecord(user(token = "ignored"))

        assertEquals(listOf(UserCache.KEY_USER), stores.writes)
        assertTrue(recorder.signedIn.isEmpty(), "no sign-in side effects on the bind path")
    }

    @Test
    fun `signing in reports the account to the side effects`() {
        val recorder = Recorder()
        val manager = UserManager(FakeUserRepository(), UserCache(OrderingStores()), recorder)

        manager.store(user(userId = 21L))

        assertEquals(listOf(21L), recorder.signedIn)
    }

    @Test
    fun `storing a login result writes the recovered keys and feeds the identity`() {
        val f = Fixture()

        f.manager.store(user(vipSubType = 2, token = "jwt-abc"))

        assertTrue(f.cache.isVip())
        assertEquals("jwt-abc", f.cache.token())
        assertEquals(7L, f.cache.userId())
        val raw = f.stores.shared.string(UserCache.KEY_USER).orEmpty()
        assertTrue(raw.contains("\"userId\":7"), "the account is stored as JSON under user2, got $raw")
        assertEquals("jwt-abc", f.stores.shared.string(UserCache.KEY_TOKEN))
    }

    @Test
    fun `the request identity is read from the same cache the login wrote`() {
        val f = Fixture()
        f.manager.store(user(userId = 55L, token = "t-55"))
        val device = AndroidDeviceContext(ApplicationProvider.getApplicationContext<Context>(), f.cache)

        assertEquals("t-55", device.authToken())
        assertEquals(55L, device.userId)
        assertEquals(55L, device.mainUser)
    }

    @Test
    fun `refresh replaces the cache and publishes the new record`() = runBlocking {
        val f = Fixture()
        f.repository.next = ApiResponse(200, "success", data = user(userId = 9L))

        f.manager.refresh()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, f.repository.calls)
        assertEquals(9L, f.cache.userId())
        assertEquals(9L, f.manager.user.value?.userId)
    }

    @Test
    fun `a failed refresh leaves the cache alone`() = runBlocking {
        val f = Fixture()
        f.manager.store(user(userId = 3L, token = "keep"))

        val response = f.manager.refresh()

        assertFalse(response.isSuccess)
        assertEquals(3L, f.cache.userId())
        assertEquals("keep", f.cache.token())
        assertTrue(f.effects.events.isEmpty())
    }

    @Test
    fun `a successful response with no record is not written either`() = runBlocking {
        val f = Fixture()
        f.manager.store(user(userId = 3L))
        f.repository.next = ApiResponse(200, "success", data = null)

        f.manager.refresh()

        assertEquals(3L, f.cache.userId())
    }

    @Test
    fun `vip side effects fire only on a change, or when forced`() = runBlocking {
        val f = Fixture()
        f.repository.next = ApiResponse(200, "success", data = user(userId = 1L, vipSubType = 0))
        f.manager.refresh()
        assertTrue(f.effects.events.isEmpty(), "non-vip to non-vip is not a change")

        f.repository.next = ApiResponse(200, "success", data = user(userId = 1L, vipSubType = 5))
        f.manager.refresh()
        assertEquals(listOf(1L to false), f.effects.events)

        f.effects.events.clear()
        f.manager.refresh()
        assertTrue(f.effects.events.isEmpty(), "the same vip state must not re-fire")

        f.manager.refresh(sendVipState = true)
        assertEquals(1, f.effects.events.size, "the recovered sendVipState flag forces the refresh")
    }

    @Test
    fun `a shared vip grant counts as vip once the record lands`() {
        val f = Fixture()

        f.manager.store(user(userId = 4L, vipSubType = 0).copy(shareToMeList = listOf(ShareVip(vipSubType = 1))))

        assertTrue(f.cache.isVip())
    }

    @Test
    fun `sign out resets the token and writes an empty record instead of clearing the key`() {
        val f = Fixture()
        f.manager.store(user(userId = 8L, vipSubType = 1, token = "live"))

        f.manager.signOut()

        assertEquals("", f.cache.token())
        assertEquals("", f.stores.shared.string(UserCache.KEY_TOKEN))
        assertNotNull(f.stores.shared.string(UserCache.KEY_USER), "the recovered code overwrote it, not deleted")
        assertFalse(f.cache.isVip())
        assertEquals(0L, f.cache.userId())
    }

    @Test
    fun `the key names stay the recovered ones`() {
        assertEquals("user2", UserCache.KEY_USER)
        assertEquals("token", UserCache.KEY_TOKEN)
    }
}
