package com.youloft.watcher.data

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.youloft.linkup.api.dto.ShareVip
import com.youloft.linkup.api.dto.User
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.AndroidDeviceContext
import com.youloft.watcher.data.local.InMemoryKeyValueStores
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
        override fun onVipStateChanged(user: User, wasVip: Boolean) {
            events += user.userId to wasVip
        }
    }

    private class Fixture(Initial: Map<String, String> = emptyMap()) {
        val stores = InMemoryKeyValueStores(Initial)
        val cache = UserCache(stores)
        val repository = FakeUserRepository()
        val effects = Recorder()
        val manager = UserManager(repository, cache, effects)
    }

    private fun user(userId: Long = 7L, vipSubType: Int = 0, token: String? = null) = User(
        userId = userId,
        mainUser = userId,
        vipSubType = vipSubType,
        token = token,
    )

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
