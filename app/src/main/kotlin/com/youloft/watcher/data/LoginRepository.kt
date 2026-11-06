package com.youloft.watcher.data

import com.youloft.linkup.api.dto.Cut
import com.youloft.linkup.api.dto.User
import com.youloft.linkup.net.ApiResponse
import com.youloft.linkup.net.LinkUpApi
import com.youloft.watcher.data.local.LoginFlags

/**
 * `repository/c` in the recovered app, limited to what the login screens use. The repository — not the
 * data source — is where a successful login is turned into local state, so this is the layer a test has
 * to drive to prove the write path.
 */
interface LoginRepository {
    suspend fun loginCut(): ApiResponse<Cut>

    /** GET api/sms/send. `region` is the country code the original defaulted to `+86`. */
    suspend fun sendCode(phone: String, region: String = DEFAULT_REGION): ApiResponse<Unit>

    /**
     * typeId **1 = 一键登录 token**, **2 = 短信验证码**, **3 = 号码认证 opToken**; `bindingPhone` switches
     * the endpoint to `api/user/change_bind` with the *same* body (the recovered `if` only picks the verb).
     */
    suspend fun loginByPhone(
        typeId: Int,
        phone: String? = null,
        smsCode: String? = null,
        token: String? = null,
        gyuId: String? = null,
        opToken: String? = null,
        operator: String? = null,
        region: String = DEFAULT_REGION,
        bindingPhone: Boolean = false,
    ): ApiResponse<User>

    /** POST api/user/login — the third-party path, which keys on `platform` (4 微信 / 5 QQ), not typeId. */
    suspend fun loginByThird(
        unionId: String,
        platform: Int,
        avatar: String? = null,
        nickname: String? = null,
        phone: String? = null,
    ): ApiResponse<User>

    companion object {
        const val DEFAULT_REGION = "+86"
        const val TYPE_ID_ONE_TAP_TOKEN = 1
        const val TYPE_ID_SMS = 2
        const val TYPE_ID_NUMBER_VERIFY = 3
    }
}

class NetworkLoginRepository(
    private val api: LinkUpApi,
    private val users: UserManager,
    private val flags: LoginFlags,
) : LoginRepository {

    override suspend fun loginCut(): ApiResponse<Cut> = api.smsLoginCut()

    override suspend fun sendCode(phone: String, region: String): ApiResponse<Unit> =
        api.smsSend(phone, region)

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
        val body = mutableMapOf<String, Any?>(
            "typeId" to typeId.toString(),
            "phone" to phone,
            "smsCode" to smsCode,
            "token" to token,
            "gyuId" to gyuId,
            "opToken" to opToken,
            "operator" to operator,
            "region" to region,
        )
        val response = if (bindingPhone) api.userChangeBind(body) else api.smsClickToLogin(body)
        val user = response.data
        if (response.isSuccess && user != null) {
            // `repository/c.e()`: binding a phone only replaces the cached record — no token, no sign-in.
            if (bindingPhone) users.saveRecord(user) else users.store(user, fromLogin = true)
        }
        return response
    }

    override suspend fun loginByThird(
        unionId: String,
        platform: Int,
        avatar: String?,
        nickname: String?,
        phone: String?,
    ): ApiResponse<User> {
        val body = mutableMapOf<String, Any?>(
            "unionId" to unionId,
            "platform" to platform.toString(),
            "avatar" to avatar,
            "nickname" to nickname,
            "phone" to phone,
        )
        val response = api.userLogin(body)
        val user = response.data
        if (response.isSuccess && user != null) {
            // `v0.i(..., user, false)`: a registered account is parked for profile completion instead.
            users.store(user, fromLogin = false)
            flags.recordLastLoginType(platform)
        }
        return response
    }
}
