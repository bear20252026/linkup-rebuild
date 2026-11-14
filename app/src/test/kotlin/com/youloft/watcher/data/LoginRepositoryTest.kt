package com.youloft.watcher.data

import com.youloft.linkup.api.dto.User
import com.youloft.linkup.net.ApiResponse
import com.youloft.linkup.net.LinkUpApi
import com.youloft.watcher.data.local.InMemoryKeyValueStores
import com.youloft.watcher.data.local.LoginFlags
import com.youloft.watcher.data.local.UserCache
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 登录不闪退（审计⑤）：服务端成功而本地会话写失败时，repository 用失败回答收尾——VM 走
 * toast 错误路径，而不是把未捕获异常顶进 viewModelScope。Robolectric 只为 LiveData 的主线程
 * 断言；网络栈不在场（api 用 Proxy 替身）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoginRepositoryTest {

    @Test
    fun `a login whose session cannot be stored answers failure instead of crashing`() = runBlocking {
        val api = apiAnswering(ApiResponse(200, "success", data = User(userId = 7L, nickname = "妈妈", token = "tk")))
        val repository = NetworkLoginRepository(
            api,
            UserManager(StubUserRepository(), UserCache(LoginFixturesStores.Throwing())),
            LoginFlags(InMemoryKeyValueStores()),
        )

        val response = repository.loginByPhone(
            typeId = LoginRepository.TYPE_ID_SMS,
            phone = "13800000001",
            smsCode = "000000",
        )

        assertTrue(!response.isSuccess, "the session never landed; success would navigate without a token")
        assertEquals("会话保存失败，请重试", response.msg)
    }

    @Test
    fun `a stored login keeps the server's answer untouched`() = runBlocking {
        val answer = ApiResponse(200, "success", data = User(userId = 7L, nickname = "妈妈", token = "tk"))
        val repository = NetworkLoginRepository(
            apiAnswering(answer),
            UserManager(StubUserRepository(), UserCache(InMemoryKeyValueStores())),
            LoginFlags(InMemoryKeyValueStores()),
        )

        val response = repository.loginByPhone(
            typeId = LoginRepository.TYPE_ID_SMS,
            phone = "13800000001",
            smsCode = "000000",
        )

        assertTrue(response.isSuccess)
        assertEquals(answer.data?.userId, response.data?.userId)
    }

    // ------------------------------------------------------------------ fixtures

    private object LoginFixturesStores {
        /** 写必抛：MMKV 类故障的替身。 */
        class Throwing : com.youloft.watcher.data.local.KeyValueStores {
            private val throwing = object : com.youloft.watcher.data.local.KeyValueStore {
                override fun string(key: String): String? = null
                override fun int(key: String, default: Int): Int = default
                override fun boolean(key: String, default: Boolean): Boolean = default
                override fun long(key: String, default: Long): Long = default
                override fun putLong(key: String, value: Long) = throw IllegalStateException("mmkv down")
                override fun putString(key: String, value: String) = throw IllegalStateException("mmkv down")
                override fun putInt(key: String, value: Int) = throw IllegalStateException("mmkv down")
                override fun putBoolean(key: String, value: Boolean) = throw IllegalStateException("mmkv down")
            }

            override val shared = throwing
            override fun forUser(userId: Long) = throwing
        }
    }

    private class StubUserRepository : UserRepository {
        override suspend fun userInfo() = ApiResponse<User>(500, "no fixture")
    }

    private fun apiAnswering(answer: ApiResponse<User>): LinkUpApi = Proxy.newProxyInstance(
        LinkUpApi::class.java.classLoader,
        arrayOf(LinkUpApi::class.java),
    ) { _, method, _ ->
        if (method.name == "smsClickToLogin") answer else error("unexpected call: " + method.name)
    } as LinkUpApi
}
