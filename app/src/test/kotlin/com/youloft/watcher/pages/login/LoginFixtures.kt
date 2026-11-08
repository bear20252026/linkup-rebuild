package com.youloft.watcher.pages.login

import com.youloft.linkup.api.dto.Cut
import com.youloft.linkup.api.dto.User
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.data.LoginRepository
import com.youloft.watcher.data.LinkUpApplication
import com.youloft.watcher.data.local.InMemoryKeyValueStores
import com.youloft.watcher.data.local.KeyValueStores
import com.youloft.watcher.data.local.UserCache

/**
 * Shared doubles for the rebuilt login screen. Robolectric builds the Application before the test
 * instance exists, so every double lives in this holder and each test resets it.
 */
object LoginFixtures {
    var repository = FakeLoginRepository()
    var stores = InMemoryKeyValueStores()
    var oneTap = RecordingOneTapAuth()

    fun reset() {
        repository = FakeLoginRepository()
        stores = InMemoryKeyValueStores()
        oneTap = RecordingOneTapAuth()
    }

    /** The cached account the login screen reads its "logged in yet" state from. */
    fun seedAccount(userId: Long = 42L, nickname: String = "小明", token: String = "tk") {
        stores.shared.putString(
            UserCache.KEY_USER,
            """{"userId":$userId,"nickname":"$nickname","token":"$token","isRegister":false}""",
        )
    }

    class TestApplication : LinkUpApplication() {
        override val keyValueStores: KeyValueStores get() = stores
        override val loginRepository: LoginRepository get() = repository
        override val oneTapAuth: OneTapAuth get() = oneTap
    }

    /** Records the four login calls and answers with whatever the test loaded. */
    class FakeLoginRepository : LoginRepository {
        var cut = ApiResponse(200, "success", data = Cut(loginType = "1"))
        var cutFailure: Throwable? = null

        var codeSent = ApiResponse<Unit>(200, "success")
        var sendFailure: Throwable? = null

        var login = ApiResponse(200, "success", data = User(userId = 7L, nickname = "刚登录的人", token = "new-token"))
        var loginFailure: Throwable? = null

        var third = ApiResponse(200, "success", data = User(userId = 8L, nickname = "微信用户", token = "wx-token"))

        val sentPhones = mutableListOf<Pair<String, String>>()
        val phoneLogins = mutableListOf<PhoneLogin>()
        val thirdLogins = mutableListOf<ThirdLogin>()
        var cutCalls = 0

        override suspend fun register(username: String, password: String, inviteCode: String) =
            ApiResponse(500, "test stub", data = User())

        override suspend fun passwordLogin(username: String, password: String) =
            ApiResponse(500, "test stub", data = User())

        override suspend fun loginCut(): ApiResponse<Cut> {
            cutFailure?.let { throw it }
            cutCalls++
            return cut
        }

        override suspend fun sendCode(phone: String, region: String): ApiResponse<Unit> {
            sendFailure?.let { throw it }
            sentPhones += phone to region
            return codeSent
        }

        override suspend fun loginByPhone(
            typeId: Int,
            phone: String?,
            smsCode: String?,
            token: String?,
            gyuId: String?,
            opToken: String?,
            operator: String?,
            region: String,
            bindingPhone: Boolean,
        ): ApiResponse<User> {
            loginFailure?.let { throw it }
            phoneLogins += PhoneLogin(typeId, phone, smsCode, token, gyuId, opToken, operator, region, bindingPhone)
            return login
        }

        override suspend fun loginByThird(
            unionId: String,
            platform: Int,
            avatar: String?,
            nickname: String?,
            phone: String?,
        ): ApiResponse<User> {
            loginFailure?.let { throw it }
            thirdLogins += ThirdLogin(unionId, platform, avatar, nickname, phone)
            return third
        }

        data class PhoneLogin(
            val typeId: Int,
            val phone: String?,
            val smsCode: String?,
            val token: String?,
            val gyuId: String?,
            val opToken: String?,
            val operator: String?,
            val region: String,
            val bindingPhone: Boolean,
        )

        data class ThirdLogin(
            val unionId: String,
            val platform: Int,
            val avatar: String?,
            val nickname: String?,
            val phone: String?,
        )
    }

    class RecordingOneTapAuth : OneTapAuth {
        val requested = mutableListOf<Int>()
        var answer: OneTapAccount? = null
        var calls = 0

        override fun preLogin(loginType: Int, callback: (OneTapAccount?) -> Unit) {
            calls++
            requested += loginType
            callback(answer)
        }
    }

    class RecordingLoginNavigation : LoginNavigation {
        val calls = mutableListOf<String>()

        override fun openImproveUserInfo(avatar: String, nickname: String, fromH5: Boolean, matchCode: Long) {
            calls += "improve(avatar=$avatar,nickname=$nickname,fromH5=$fromH5,match=$matchCode)"
        }

        override fun openMain() {
            calls += "main"
        }

        override fun openPrivacyPolicy() {
            calls += "privacy"
        }

        override fun openUserAgreement() {
            calls += "agreement"
        }

        override fun openCarrierPolicy(url: String, title: String) {
            calls += "carrier(url=$url,title=$title)"
        }
    }

    class RecordingLoginDialogs : LoginDialogs {
        val calls = mutableListOf<String>()

        override fun showSmsDialog(matchCode: Long) {
            calls += "sms(match=$matchCode)"
        }

        override fun showOneTapDialog(account: OneTapAccount) {
            calls += "oneTap(number=${account.number})"
        }
    }

    class RecordingThirdPartyAuth : ThirdPartyAuth {
        val calls = mutableListOf<String>()
        var wechatInstalled = true
        var qqInstalled = true
        var customerServiceSupported = true

        override fun isWechatInstalled(): Boolean = wechatInstalled

        override fun isQqInstalled(): Boolean = qqInstalled

        override fun sendWechatAuth() {
            calls += "wechatAuth"
        }

        override fun openCustomerServiceChat(): Boolean {
            calls += "customerService"
            return customerServiceSupported
        }

        override fun startQqLogin(activity: android.app.Activity) {
            calls += "qqLogin"
        }

        override fun handleQqResult(requestCode: Int, resultCode: Int, data: android.content.Intent?): Boolean {
            calls += "qqResult:$requestCode:$resultCode"
            return false
        }
    }
}
