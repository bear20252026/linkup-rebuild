package com.youloft.watcher.pages.login

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.youloft.linkup.api.dto.Cut
import com.youloft.linkup.api.dto.User
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.data.LoginRepository
import com.youloft.watcher.data.local.LoginFlags
import kotlinx.coroutines.launch

/**
 * `base/viewmodel/BaseLoginViewModel`: the half every login-shaped screen shares — ask for a code, then
 * exchange it (or a vendor token) for an account. `LoginSmsDialog`, `LoginPhoneDialog` and the two
 * bind-phone screens were all built on it, so the split is kept rather than inlined.
 *
 * Both channels are *posted*, not set: the recovered code ran every request off the main thread.
 */
open class BaseLoginViewModel(protected val repository: LoginRepository) : ViewModel() {

    private val _loginResult = MutableLiveData<ApiResponse<User>>()

    /** `e()` — what `api/sms/click_to_login` (or `change_bind`) answered, good or bad. */
    val loginResult: LiveData<ApiResponse<User>> = _loginResult

    private val _codeSent = MutableLiveData<ApiResponse<Unit>>()

    /** `f()` — what `api/sms/send` answered. */
    val codeSent: LiveData<ApiResponse<Unit>> = _codeSent

    /** `k(phone)` — nothing else happens here: no analytics, no retry (the data source toasts on error). */
    fun sendCode(phone: String) {
        viewModelScope.launch { _codeSent.postValue(repository.sendCode(phone)) }
    }

    /** `g(typeId, …)` — one channel for all three phone-shaped logins. */
    fun loginByPhone(
        typeId: Int,
        phone: String? = null,
        smsCode: String? = null,
        token: String? = null,
        gyuId: String? = null,
        opToken: String? = null,
        operator: String? = null,
        bindingPhone: Boolean = false,
    ) {
        viewModelScope.launch {
            val response = repository.loginByPhone(
                typeId = typeId,
                phone = phone,
                smsCode = smsCode,
                token = token,
                gyuId = gyuId,
                opToken = opToken,
                operator = operator,
                bindingPhone = bindingPhone,
            )
            if (!response.isSuccess) loginFailType(typeId, response.msg)
            _loginResult.postValue(response)
        }
    }

    /** `i(phone, code, bindingPhone)` — the SMS path: typeId 2 and nothing else filled in. */
    fun loginBySms(phone: String, smsCode: String, bindingPhone: Boolean = false) {
        loginByPhone(LoginRepository.TYPE_ID_SMS, phone = phone, smsCode = smsCode, bindingPhone = bindingPhone)
    }

    /**
     * The `type` value of the `login_fail` event — `手机号-<typeId>-<msg>`, assembled exactly as the
     * recovered code did it (the sink itself is a separate card, so nothing is sent).
     */
    internal fun loginFailType(typeId: Int, message: String?): String = "手机号-$typeId-$message"

    companion object {
        /** `CommonNetImpl.FAIL`, the literal the original reported as the failure code. */
        const val FAIL_CODE = "-1"
    }
}

/** `pages/login/viewmodel/LoginSmsViewModel` — an empty subclass in the original, kept for the shape. */
class LoginSmsViewModel(repository: LoginRepository) : BaseLoginViewModel(repository)

/**
 * `pages/login/viewmodel/LoginViewModel`: the screen-level state on top of the shared login half —
 * which login type the backend wants, and the WeChat / QQ exchange, which is a **different endpoint**
 * (`api/user/login`, keyed on `platform`) rather than another typeId.
 */
class LoginViewModel(
    repository: LoginRepository,
    private val flags: LoginFlags,
) : BaseLoginViewModel(repository) {

    private val _cut = MutableLiveData<ApiResponse<Cut>>()

    /** `q()` — `api/sms/login/cut`; its `loginType` picks the one-tap vendor. */
    val cut: LiveData<ApiResponse<Cut>> = _cut

    private val _thirdPartyResult = MutableLiveData<ApiResponse<User>>()

    /** `p()` — the answer of `api/user/login`. */
    val thirdPartyResult: LiveData<ApiResponse<User>> = _thirdPartyResult

    private val _thirdPartyProfile = MutableLiveData<User>()

    /** `r()` — the avatar/nickname pulled from the vendor, parked for the profile-completion screen. */
    val thirdPartyProfile: LiveData<User> = _thirdPartyProfile

    /** `u()` — called once, from `onCreate`. */
    fun loginCut() {
        viewModelScope.launch { _cut.postValue(repository.loginCut()) }
    }

    /** `s(unionId, platform, …)` — 4 = 微信, 5 = QQ. */
    fun loginByThird(
        unionId: String,
        platform: Int,
        avatar: String? = null,
        nickname: String? = null,
        phone: String? = null,
    ) {
        viewModelScope.launch {
            val response = repository.loginByThird(unionId, platform, avatar, nickname, phone)
            if (response.isSuccess) {
                flags.recordLastLoginType(platform)
            } else {
                thirdPartyFailType(platform)
            }
            _thirdPartyResult.postValue(response)
        }
    }

    /** The `login_fail` `type` for this path: platform 4 reports 微信, anything else the QQ constant. */
    internal fun thirdPartyFailType(platform: Int): String =
        if (platform == LoginFlags.PLATFORM_WECHAT) WECHAT_LABEL else QQ_LABEL

    /**
     * `publishWechatProfile` — the tail of `t(code)`: the vendor profile is parked here whether or not
     * the exchange finished, because the recovered code posted it even when it threw.
     */
    internal fun publishThirdPartyProfile(user: User) {
        _thirdPartyProfile.postValue(user)
    }

    companion object {
        const val WECHAT_LABEL = "微信"

        /** `Constants.SOURCE_QQ`, the label the original reported for QQ. */
        const val QQ_LABEL = "QQ"

        fun factory(repository: LoginRepository, flags: LoginFlags) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                when (modelClass) {
                    LoginViewModel::class.java -> LoginViewModel(repository, flags)
                    LoginSmsViewModel::class.java -> LoginSmsViewModel(repository)
                    else -> error("unexpected view model ${modelClass.name}")
                } as T
        }
    }
}
