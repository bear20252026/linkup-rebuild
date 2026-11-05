package com.youloft.watcher.pages.login

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.youloft.watcher.R

/**
 * What the carrier number-auth SDK can answer before the user types anything. Recovered as
 * `bean/login/SecurityInfo`, filled from `GYManager.getPreLoginResult()` (`privacyUrl`, `privacyName`)
 * plus the SDK payload (`number`, `operatorType`, `operatorTypeStr`, `gyuid`).
 *
 * Only `number` and `operatorTypeStr` decide which dialog the screen opens, and only the privacy pair
 * reaches the agreement text — the rest is carried because the one-tap login sends it back.
 */
data class OneTapAccount(
    val number: String = "",
    val operatorType: String = "",
    val operatorTypeStr: String = "",
    val gyuid: String? = null,
    val privacyName: String = "",
    val privacyUrl: String = "",
)

/**
 * The one-tap login SDK surface the screens need. Two vendors are in the shipped APK: `com.g.gysdk`
 * (loginType 1) and `cn.fly.verify.FlyVerify` (loginType 3, which replaced 1.5.6's
 * `com.mob.secverify.SecPure`) — **neither jar is obtainable from the APK**, and neither is on Maven
 * Central, so production answers "nothing cached", which is exactly the branch the recovered code takes
 * when the pre-login call fails or the login type is unknown.
 */
interface OneTapAuth {
    /** `v0.o(loginType, cb)`: 1 → GY, 3 → FlyVerify, anything else → `cb(null)`. */
    fun preLogin(loginType: Int, callback: (OneTapAccount?) -> Unit)
}

object UnavailableOneTapAuth : OneTapAuth {
    override fun preLogin(loginType: Int, callback: (OneTapAccount?) -> Unit) {
        callback(null)
    }
}

/**
 * WeChat / QQ authorisation. The install checks are the recovered mechanism (a plain package query,
 * which is what the original did too, against the two `<queries>` entries in the manifest); the
 * authorisation calls themselves need `com.tencent.mm.opensdk` and the QQ connector, neither of which is
 * a dependency yet — so those two are recorded, not invented.
 */
interface ThirdPartyAuth {
    fun isWechatInstalled(): Boolean

    fun isQqInstalled(): Boolean

    /** `WXEntryActivity.c.a(api)` — send the auth request. The code comes back through [LoginEvents]. */
    fun sendWechatAuth()

    /** `WXOpenCustomerServiceChat.Req`, gated on `getWXAppSupportAPI() >= 671090490`. */
    fun openCustomerServiceChat(): Boolean

    /** `Tencent.login(activity, "get_simple_userinfo", uiListener)`. */
    fun startQqLogin(activity: Activity)

    /** `Tencent.onActivityResultData` + the 10100/11101 branch; true when the intent was theirs. */
    fun handleQqResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean
}

class PackageQueryThirdPartyAuth(private val context: Context) : ThirdPartyAuth {
    override fun isWechatInstalled(): Boolean = isInstalled(WECHAT_PACKAGE)

    override fun isQqInstalled(): Boolean = isInstalled(QQ_PACKAGE)

    override fun sendWechatAuth() = Unit

    override fun openCustomerServiceChat(): Boolean = false

    override fun startQqLogin(activity: Activity) = Unit

    override fun handleQqResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean = false

    private fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val QQ_PACKAGE = "com.tencent.mobileqq"

        /** `ag.a.f1738i`, the app id the shipped APK authenticates with. */
        const val WECHAT_APP_ID = "wxccdc26e51e8c56ce"

        /** The `102361775` inside the `tencent…` scheme of the recovered manifest. */
        const val QQ_APP_ID = 102_361_775

        /** The minimum WeChat API level for the customer-service chat (measured in `LoginActivity.M0`). */
        const val MIN_WECHAT_API_FOR_CUSTOMER_SERVICE = 671090490
    }
}

/**
 * The hand-offs the SDK callbacks make back into the screen. The recovered code used EventBus for the
 * WeChat code (`WxCode`) and an `IUiListener` for QQ; both end in the same place, so they are typed
 * flows here, keyed by what the screen does with them.
 */
object LoginEvents {
    private val _wechatCodes = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val _qqProfiles = kotlinx.coroutines.flow.MutableSharedFlow<QQProfile>(extraBufferCapacity = 1)
    private val _authErrors = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** `getWxCode(WxCode)` — an empty code is ignored by the screen, so it is not emitted here. */
    val wechatCodes: kotlinx.coroutines.flow.SharedFlow<String> = _wechatCodes

    /** The `figureurl_qq` / `nickname` pair the QQ listener handed back. */
    val qqProfiles: kotlinx.coroutines.flow.SharedFlow<QQProfile> = _qqProfiles

    /** Anything the vendor reported as a failure; the screen toasts it, or `login_error_hint`. */
    val authErrors: kotlinx.coroutines.flow.SharedFlow<String> = _authErrors

    fun publishWechatCode(code: String) {
        if (code.isNotEmpty()) _wechatCodes.tryEmit(code)
    }

    fun publishQqProfile(profile: QQProfile) {
        _qqProfiles.tryEmit(profile)
    }

    fun publishAuthError(message: String) {
        _authErrors.tryEmit(message)
    }

    /** The two fields `LoginActivity` needs from either vendor profile. */
    data class QQProfile(val avatar: String = "", val nickname: String = "")
}

/** The string the screen shows when a vendor failed without saying why. */
fun Context.thirdPartyLoginError(message: String) {
    val text = message.ifEmpty { getString(R.string.login_error_hint) }
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
