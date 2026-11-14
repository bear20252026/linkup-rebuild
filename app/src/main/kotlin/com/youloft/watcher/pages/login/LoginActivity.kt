package com.youloft.watcher.pages.login

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.youloft.linkup.api.dto.User
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.data.local.LoginFlags
import com.youloft.watcher.databinding.ActivityLoginBinding
import com.youloft.watcher.ext.enableSpanClicks
import com.youloft.watcher.ext.onIntervalClick
import com.youloft.watcher.ext.withClickableSpan
import com.youloft.watcher.pages.common.LoadingDialog
import com.youloft.watcher.pages.login.dialog.LoginSmsDialog
import com.youloft.watcher.pages.login.dialog.PolicyRemindDialog
import kotlinx.coroutines.launch

/**
 * Recovered `pages.login.LoginActivity` (1.6.6, 937 lines). This is the screen every locked door in the
 * app routes to, and it offers four ways in: WeChat, QQ, the carrier's one-tap number, and a短信验证码.
 *
 * What the rebuild owns here is the *decision tree*, because that is the part a test can pin:
 *  - `api/sms/login/cut` picks the one-tap vendor by `loginType` (1 GY, 3 FlyVerify, anything else means
 *    "no vendor"), and a failed or empty pre-login is what drops the user into the SMS sheet;
 *  - the agreement checkbox gates **all three** buttons, and confirming the reminder dialog ticks the box
 *    for the user before continuing;
 *  - a WeChat / QQ press only checks installation locally — the authorisation itself is the SDK's card.
 *
 * The vendor pieces are seams (`OneTapAuth`, `ThirdPartyAuth`) because neither jar can be taken out of
 * the APK, and `LoginPhoneDialog` is the second half of this card, so `dialogs` records which sheet the
 * screen *chose* rather than faking the one it cannot show yet.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private val graph get() = appGraph(this)

    private val viewModel: LoginViewModel by viewModels {
        LoginViewModel.factory(graph.loginRepository, graph.loginFlags)
    }

    private val smsViewModel: LoginSmsViewModel by viewModels {
        LoginViewModel.factory(graph.loginRepository, graph.loginFlags)
    }

    var navigation: LoginNavigation = DefaultLoginNavigation(this)

    var dialogs: LoginDialogs = RealLoginDialogs(this) { smsViewModel }

    var platform: ThirdPartyAuth = PackageQueryThirdPartyAuth(this)

    /**
     * The carrier one-tap SDK. The default is the app-wide `UnavailableOneTapAuth` — no jar can be taken
     * out of the APK, so the honest answer is "no cached number", which lands the user in the SMS sheet.
     */
    var oneTap: OneTapAuth = UnavailableOneTapAuth

    /** `f64800v`…`f64804z` — whatever the carrier SDK last handed back. */
    private var security: OneTapAccount = OneTapAccount()

    /** `A` — the vendor profile a third-party login pulled, used to prefill the next screen. */
    private var thirdPartyProfile: User? = null

    private var fromH5 = false

    private var matchCode = 0L

    private var loading: LoadingDialog? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the original ignored both answers */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        addAuthLinks()
        window.statusBarColor = getColor(R.color.colorWhite)

        // `lf.b.i(..., "登录注册页")` — the label the original reported; its sink is a separate card.
        fromH5 = intent.getBooleanExtra(EXTRA_FROM_H5, false)
        matchCode = intent.getLongExtra(EXTRA_MATCH_CODE, 0L)
        if (intent.getBooleanExtra(EXTRA_LOGOUT, false)) showLastLoginBadge(graph.loginFlags.lastLoginType())

        fadeInDecoration()

        // 第三方登录暂停（ENABLE_THIRD_PARTY_LOGIN）：隐藏微信/QQ 入口，点击链原样保留，
        // 恢复时只翻开关。
        if (!ENABLE_THIRD_PARTY_LOGIN) {
            binding.tvLoginWechat.visibility = View.GONE
            binding.ivLoginQq.visibility = View.GONE
        }

        binding.tvLoginWechat.onIntervalClick { loginWithWechat() }
        binding.ivLoginPhone.onIntervalClick { loginWithPhone() }
        binding.ivLoginQq.onIntervalClick { loginWithQq() }
        binding.ivCs.onIntervalClick { openCustomerService() }

        requestNotificationPermissionOnce()

        viewModel.cut.observe(this) { onLoginCut() }
        viewModel.thirdPartyResult.observe(this) { response ->
            hideLoading()
            if (response == null || !response.isSuccess) {
                response?.msg?.takeIf { it.isNotEmpty() }?.let { toast(it) }
                return@observe
            }
            val user = response.data ?: return@observe
            if (user.isRegister) {
                val profile = thirdPartyProfile
                navigation.openImproveUserInfo(
                    avatar = profile?.avatar.orEmpty(),
                    nickname = profile?.nickname.orEmpty(),
                    fromH5 = fromH5,
                    matchCode = matchCode,
                )
                // `lf.b.g(..., "经典地图")` — the label the original reported on this branch.
            } else {
                navigation.openMain()
            }
            finish()
        }
        viewModel.thirdPartyProfile.observe(this) { thirdPartyProfile = it }

        viewModel.loginCut()
    }

    override fun onResume() {
        super.onResume()
        // The original re-rendered the agreement line on every resume, because the carrier's own privacy
        // terms can arrive after the screen is already up.
        renderAgreement(security.privacyName, security.privacyUrl)
    }

    @Deprecated("The recovered screen read QQ's result through the SDK's own static path.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        platform.handleQqResult(requestCode, resultCode, data)
        // Unconditional, exactly as recovered: any result re-enables the button.
        binding.ivLoginQq.isEnabled = true
    }

    /** `a(context, fromH5, matchCode, noAnim, logout)` — one 500ms gate for the whole process. */
    private fun openRegister() {
        RegisterActivity.start(this)
    }

    /** 密码登录对话框（用户名 + 密码 → api/user/password_login）。 */
    private fun showPasswordLoginDialog() {
        val dp = resources.displayMetrics.density
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((48 * dp).toInt(), (24 * dp).toInt(), (48 * dp).toInt(), 0)
        }
        val etUser = EditText(this).apply { hint = "用户名"; setSingleLine(true) }
        container.addView(etUser)
        val etPass = EditText(this).apply {
            hint = "密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        container.addView(etPass)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("密码登录")
            .setView(container)
            .setPositiveButton("登录") { _, _ ->
                val username = etUser.text?.toString()?.trim() ?: ""
                val password = etPass.text?.toString()?.trim() ?: ""
                if (username.isBlank() || password.isBlank()) {
                    Toast.makeText(this, "请填写用户名和密码", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        if (loggingIn) return@launch
                        loggingIn = true
                        val response = appGraph(this@LoginActivity).loginRepository.passwordLogin(username, password)
                        val user = response.data
                        if (response.isSuccess && user != null && user.userId > 0) {
                            appGraph(this@LoginActivity).users.store(user)
                            com.youloft.watcher.pages.main.MainActivity.start(this@LoginActivity)
                            finish()
                        } else {
                            loggingIn = false
                            Toast.makeText(this@LoginActivity, response.msg ?: "登录失败", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        loggingIn = false
                        Toast.makeText(this@LoginActivity, "网络错误：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private var loggingIn = false

    /** 底部入口：密码登录 + 账号注册（在 setContentView 之后调用）。 */
    private fun addAuthLinks() {
        val dp = resources.displayMetrics.density
        // Find the root view and add a bottom-aligned horizontal container.
        val root = binding.root as? android.view.ViewGroup ?: return
        root.post {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                setPadding(0, (16 * dp).toInt(), 0, (24 * dp).toInt())
            }
            row.addView(android.widget.TextView(this).apply {
                text = "密码登录"
                textSize = 14f
                setTextColor(0xFF2C7BE5.toInt())
                setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), 0)
                setOnClickListener { showPasswordLoginDialog() }
            })
            row.addView(android.widget.TextView(this).apply {
                text = "|"
                textSize = 14f
                setTextColor(0xFFCCCCCC.toInt())
            })
            row.addView(android.widget.TextView(this).apply {
                text = "账号注册"
                textSize = 14f
                setTextColor(0xFF2C7BE5.toInt())
                setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), 0)
                setOnClickListener { RegisterActivity.start(this@LoginActivity) }
            })
            root.addView(row)
        }
    }

    companion object {
        const val EXTRA_FROM_H5 = "from_h5"
        const val EXTRA_MATCH_CODE = "match_code"
        const val EXTRA_LOGOUT = "logout"

        /** `Tencent.login` scope, `H`. */
        const val QQ_SCOPE = "get_simple_userinfo"

        /** `setFlags(268468224)` — NEW_TASK | CLEAR_WHEN_TASK_RESET. */
        const val START_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET

        private const val START_INTERVAL_MILLIS = 500L

        /** The `loginType` values of `api/sms/login/cut`: 1 is the GY SDK, 3 the FlyVerify one. */
        const val ONE_TAP_GY = 1
        const val ONE_TAP_FLY = 3

        /** `F`/`G` of the fade — 500ms, started 100ms after the background view is attached. */
        const val FADE_MILLIS = 500L
        const val FADE_DELAY_MILLIS = 100L

        private var lastStartAt = 0L

        @JvmStatic
        @JvmOverloads
        fun start(
            context: Context?,
            fromH5: Boolean = false,
            matchCode: Long = 0L,
            noAnim: Boolean = false,
            logout: Boolean = false,
        ) {
            if (context == null) return
            val now = System.currentTimeMillis()
            if (now - lastStartAt < START_INTERVAL_MILLIS) return
            lastStartAt = now
            context.startActivity(
                Intent(context, LoginActivity::class.java).apply {
                    putExtra(EXTRA_FROM_H5, fromH5)
                    putExtra(EXTRA_MATCH_CODE, matchCode)
                    putExtra(EXTRA_LOGOUT, logout)
                    flags = START_FLAGS
                },
            )
            if (noAnim) (context as? Activity)?.overridePendingTransition(0, 0)
        }
    }

    /** Which sheet the screen opens: SMS whenever the carrier gave us nothing to work with. */
    internal fun picksSmsDialog(): Boolean =
        security.number.isEmpty() || security.operatorTypeStr.isEmpty()

    private fun onLoginCut() {
        val loginType = viewModel.cut.value?.data?.loginType?.toIntOrNull() ?: ONE_TAP_GY
        oneTap.preLogin(loginType) { info ->
            if (info == null) {
                // `W0()`: nothing to show, so the agreement line loses the carrier clause.
                renderAgreement("", "")
                return@preLogin
            }
            security = info
            if (info.number.isNotEmpty()) renderAgreement(info.privacyName, info.privacyUrl)
        }
    }

    private fun loginWithWechat() = requireAgreement {
        if (!platform.isWechatInstalled()) {
            toast(R.string.please_install_wx_first)
            return@requireAgreement
        }
        platform.sendWechatAuth()
    }

    private fun loginWithPhone() = requireAgreement {
        if (picksSmsDialog()) dialogs.showSmsDialog(matchCode) else dialogs.showOneTapDialog(security)
    }

    private fun loginWithQq() {
        if (binding.cbReadPrivacyPolicy.isChecked) {
            qqLogin(disableButton = false)
            return
        }
        // Recovered asymmetry kept: `S0()` (a direct press) never disables the button, while the
        // continuation `T0()` — reached only through the reminder sheet — does.
        requireAgreement { qqLogin(disableButton = true) }
    }

    private fun qqLogin(disableButton: Boolean) {
        if (!platform.isQqInstalled()) {
            toast(R.string.please_install_qq_first)
            return
        }
        if (disableButton) binding.ivLoginQq.isEnabled = false
        platform.startQqLogin(this)
    }

    private fun openCustomerService() {
        if (!platform.isWechatInstalled()) {
            toast(R.string.please_install_wx_first)
            return
        }
        platform.openCustomerServiceChat()
        // `helper.b.o(false)` — AdControlHelper, whose card is not rebuilt, so nothing to turn off here.
    }

    /**
     * `h1()` — the checkbox gate. Confirming the reminder sets the checkbox *and* replays the press, so
     * the user never has to tap twice.
     */
    private fun requireAgreement(action: () -> Unit) {
        if (binding.cbReadPrivacyPolicy.isChecked) {
            action()
            return
        }
        PolicyRemindDialog(
            context = this,
            carrierName = security.privacyName,
            carrierUrl = security.privacyUrl,
            hidePlatformPolicies = false,
            onConfirm = {
                binding.cbReadPrivacyPolicy.isChecked = true
                action()
            },
        ).show()
    }

    /**
     * `V0(name, url)` — `我已阅读并同意《隐私政策》《用户协议》和《carrier》`, where the leading phrase is
     * itself clickable and toggles the checkbox, and each document opens the web card.
     */
    private fun renderAgreement(carrierName: String, carrierUrl: String) {
        if (!::binding.isInitialized) return
        val privacy = getString(R.string.privacy_policy_u)
        val agreement = getString(R.string.user_agreement_u)
        val terms = if (carrierName.isNotEmpty()) getString(R.string.service_terms_text, carrierName) else ""
        val joiner = if (terms.isNotEmpty()) getString(R.string.and) else ""
        val base = getString(R.string.i_read_and_agree) + privacy + agreement + joiner + terms
        val leadColor = getColor(R.color.appPrimary)
        val linkColor = getColor(R.color.colorSpanClick)

        var text: CharSequence = base
        text = text.withClickableSpan(getString(R.string.i_read_and_agree), leadColor) {
            binding.cbReadPrivacyPolicy.isChecked = !binding.cbReadPrivacyPolicy.isChecked
        }
        text = text.withClickableSpan(privacy, linkColor, bold = true) { navigation.openPrivacyPolicy() }
        text = text.withClickableSpan(agreement, linkColor, bold = true) { navigation.openUserAgreement() }
        if (terms.isNotEmpty()) {
            text = text.withClickableSpan(terms, linkColor, bold = true) {
                navigation.openCarrierPolicy(carrierUrl, carrierName)
            }
        }
        binding.tvReadPrivacyPolicy.enableSpanClicks()
        binding.tvReadPrivacyPolicy.text = text
    }

    /** `O()` — both decorations fade in together, 500ms, started 100ms after the background is attached. */
    private fun fadeInDecoration() {
        val set = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.svgaLeftTop, View.ALPHA, 0f, 1f).setDuration(FADE_MILLIS),
                ObjectAnimator.ofFloat(binding.svgaRightBottom, View.ALPHA, 0f, 1f).setDuration(FADE_MILLIS),
            )
        }
        binding.ivBg.postDelayed({ set.start() }, FADE_DELAY_MILLIS)
    }

    /** `if (logout)` — 4 微信 / 5 QQ / 6 手机号, read from `lastLoginType`. */
    private fun showLastLoginBadge(lastLoginType: Int) {
        when (lastLoginType) {
            LoginFlags.PLATFORM_WECHAT -> binding.tvLastLoginWechat.visibility = View.VISIBLE
            LoginFlags.PLATFORM_QQ -> binding.tvLastLoginQq.visibility = View.VISIBLE
            LoginFlags.LOGIN_TYPE_PHONE -> binding.tvLastLoginPhone.visibility = View.VISIBLE
        }
    }

    /** `U0()` — once per account, and the answer is thrown away either way. */
    private fun requestNotificationPermissionOnce() {
        val userId = graph.accountCache.userId()
        if (graph.loginFlags.notificationRequested(userId)) return
        graph.loginFlags.markNotificationRequested(userId)
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hideLoading() {
        loading?.dismissAfterMinimum()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun toast(messageRes: Int) = Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
}

/**
 * The two sheets behind the phone button. `showOneTapDialog` is the second half of this card
 * (`LoginPhoneDialog`, which needs the vendor jars), so the default records the choice instead of
 * inventing a sheet the recovered screen would never have opened with this data.
 */
interface LoginDialogs {
    fun showSmsDialog(matchCode: Long)

    fun showOneTapDialog(account: OneTapAccount)
}

class RealLoginDialogs(
    private val activity: Activity,
    private val smsViewModel: () -> LoginSmsViewModel,
) : LoginDialogs {

    override fun showSmsDialog(matchCode: Long) {
        LoginSmsDialog(activity, smsViewModel(), matchCode).show()
    }

    override fun showOneTapDialog(account: OneTapAccount) = Unit
}
