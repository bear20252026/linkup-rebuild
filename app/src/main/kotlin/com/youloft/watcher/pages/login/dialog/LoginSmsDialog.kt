package com.youloft.watcher.pages.login.dialog

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.mc.fastkit.dialog.BindingDialog
import com.mc.fastkit.utils.KeyboardUtils
import com.youloft.linkup.api.dto.User
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.data.local.LoginFlags
import com.youloft.watcher.databinding.DialogLoginSmsBinding
import com.youloft.watcher.ext.onIntervalClick
import com.youloft.watcher.pages.common.LoadingDialog
import com.youloft.watcher.pages.login.DefaultLoginNavigation
import com.youloft.watcher.pages.login.LoginNavigation
import com.youloft.watcher.pages.login.LoginSmsViewModel

/**
 * Recovered `pages.login.dialog.LoginSmsDialog` — the bottom sheet behind "手机号登录" whenever the
 * carrier SDK could not hand over a masked number.
 *
 * Three recovered rules are the reason this is a screen-shaped class rather than two EditTexts:
 *  - the lengths are **exactly 11 and 6** (`maxLength` in the layout, `== 11` / `== CODE_LENGTH` in the watchers;码长随服务端 6 位,审计 P1-3),
 *    and the code field auto-submits the moment both are met — the 立即登录 button is a convenience,
 *    not the only door;
 *  - while the countdown runs the **phone field is locked** (disabled, greyed, delete icon hidden) and
 *    unlocked when it finishes; that pair is what `setCountdownListeners` exists for;
 *  - a successful login routes on `isRegister`: a brand-new account goes to profile completion carrying
 *    the H5 match code through, everyone else goes straight to the main screen. Both then record
 *    `lastLoginType = 6` — the number the login page later draws the "上次登录" badge from.
 */
class LoginSmsDialog @JvmOverloads constructor(
    context: Context,
    private val viewModel: LoginSmsViewModel,
    private val matchCode: Long? = null,
    private val loginCallback: () -> Unit = {},
) : BindingDialog<DialogLoginSmsBinding>(context) {

    var navigation: LoginNavigation = DefaultLoginNavigation(context)

    private val flags: LoginFlags = appGraph(context).loginFlags

    private val loading: LoadingDialog = LoadingDialog(context, minShownMillis = 0L)

    init {
        // The recovered `isDismissOnTouchOutside` was a plain field that no call site ever set, so the
        // sheet has always ignored taps outside it — unlike the rest of the app's bottom sheets.
        setCanceledOnTouchOutside(false)
    }

    override fun onInit(binding: DialogLoginSmsBinding) {
        binding.ivBack.onIntervalClick { dismissDelayed() }

        binding.etPhone.addTextChangedListener { onPhoneChanged() }
        binding.ivDelete.onIntervalClick {
            if (binding.etPhone.text?.isNotEmpty() == true) binding.etPhone.setText(null)
        }
        binding.etCode.addTextChangedListener { onCodeChanged() }

        binding.tvSendCode.onIntervalClick { sendCode() }
        binding.tvSendCode.setCountdownListeners(
            onStart = { lockPhoneField(locked = true) },
            onFinish = { lockPhoneField(locked = false) },
        )

        binding.tvLogin.onIntervalClick { login() }

        // The recovered dialog base was lifecycle-aware (`XDialog` carried a host); the rebuilt base is a
        // plain Dialog, so the two channels are held as forever-observers and removed on dismiss.
        observe(viewModel.codeSent, ::onCodeSent)
        observe(viewModel.loginResult, ::onLoginResult)
    }

    private val liveSources = mutableListOf<LiveData<*>>()

    private val liveObservers = mutableListOf<Observer<Any?>>()

    private fun <T> observe(source: LiveData<T>, action: (T?) -> Unit) {
        val observer = Observer<T> { action(it) }
        liveSources += source
        @Suppress("UNCHECKED_CAST")
        liveObservers += observer as Observer<Any?>
        source.observeForever(observer)
    }

    private fun removeObservers() {
        liveSources.zip(liveObservers).forEach { (source, observer) ->
            (source as LiveData<Any?>).removeObserver(observer)
        }
        liveSources.clear()
        liveObservers.clear()
    }

    override fun show() {
        super.show()
        if (!isShowing) return
        followKeyboard()
        // The original asked for the IME on `onResume`, 100ms after the field was attached.
        binding.etPhone.postDelayed({ if (isShowing) KeyboardUtils.showKeyboard(binding.etPhone) }, SHOW_IME_DELAY_MILLIS)
    }

    override fun dismiss() {
        removeObservers()
        binding.tvSendCode.stopCountdown()
        window?.decorView?.let(KeyboardUtils::hideKeyboard)
        loading.dismissAfterMinimum()
        super.dismiss()
    }

    /** `b.afterTextChanged` — the raw text decides, not a trimmed copy. */
    private fun onPhoneChanged() {
        val binding = binding
        val phone = binding.etPhone.text?.toString().orEmpty()
        binding.ivDelete.visibility = if (phone.isEmpty()) View.GONE else View.VISIBLE
        binding.tvLogin.isEnabled = phone.length == PHONE_LENGTH && codeLength() == CODE_LENGTH
        binding.tvSendCode.isEnabled = phone.length == PHONE_LENGTH
    }

    /** `c.afterTextChanged` — the auto-submit. Trimming happens first, exactly as the original ordered it. */
    private fun onCodeChanged() {
        val phone = trimmedPhone()
        val code = trimmedCode()
        if (phone.length != PHONE_LENGTH || code.length != CODE_LENGTH) return
        binding.tvLogin.isEnabled = true
        login()
    }

    /** `H()` — the analytics label the original reported is `发送验证码`; its sink is a separate card. */
    private fun sendCode() {
        showLoading()
        viewModel.sendCode(trimmedPhone())
    }

    /** `C()` — the manual path: same trim, same loading, plus the keyboard goes away first. */
    private fun login() {
        val phone = trimmedPhone()
        val code = trimmedCode()
        KeyboardUtils.hideKeyboard(binding.etPhone)
        showLoading()
        viewModel.loginBySms(phone, code)
    }

    /** `D()` */
    private fun onCodeSent(response: ApiResponse<Unit>?) {
        hideLoading()
        if (response == null || !response.isSuccess) {
            response?.msg?.takeIf { it.isNotEmpty() }?.let { toast(it) }
            return
        }
        // Redundant-looking in the original too (the field is focusable already); kept because the
        // focus move is the point of the branch.
        binding.etCode.isFocusable = true
        binding.etCode.isFocusableInTouchMode = true
        binding.etCode.requestFocus()
        binding.tvSendCode.startCountdown()
    }

    /** `E()` */
    private fun onLoginResult(response: ApiResponse<User>?) {
        hideLoading()
        if (response == null || !response.isSuccess) {
            response?.msg?.takeIf { it.isNotEmpty() }?.let { toast(it) }
            return
        }
        loginCallback()
        val user = response.data ?: return
        if (user.isRegister) {
            navigation.openImproveUserInfo(
                avatar = "",
                nickname = "",
                fromH5 = (matchCode ?: 0L) > 0L,
                matchCode = matchCode ?: 0L,
            )
        } else {
            navigation.openMain()
        }
        flags.recordLastLoginType(LoginFlags.LOGIN_TYPE_PHONE)
        dismiss()
        hostActivity?.finish()
    }

    private fun lockPhoneField(locked: Boolean) {
        binding.etPhone.isEnabled = !locked
        binding.etPhone.setTextColor(
            context.getColor(if (locked) R.color.appSecondary else R.color.appPrimary),
        )
        binding.ivDelete.visibility = if (locked) View.INVISIBLE else View.VISIBLE
    }

    /**
     * `onKeyboardChanged(visible, height)` moved the sheet by the IME height **minus the status bar**,
     * never negative. The reduced dialog base has no such hook, so the app's own keyboard watcher drives
     * it here.
     */
    private fun followKeyboard() {
        val host = context as? Activity ?: return
        val statusBarHeight = statusBarHeight()
        KeyboardUtils.register(host) { visible, heightPx ->
            binding.root.translationY =
                if (visible) -(heightPx - statusBarHeight).coerceAtLeast(0).toFloat() else 0f
        }
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun trimmedPhone(): String = binding.etPhone.text?.toString()?.trim().orEmpty()

    private fun trimmedCode(): String = binding.etCode.text?.toString()?.trim().orEmpty()

    private fun codeLength(): Int = trimmedCode().length

    /** `L()` — the sheet is already non-cancelable by construction, the original re-asserted it per show. */
    private fun showLoading() = loading.show()

    private fun hideLoading() = loading.dismissAfterMinimum()

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** `f64827i` / `f64826h` — layout `maxLength` and watcher length in one place. 服务端码长 6 位（P1-3：空间 10^6 × 尝试不重置 × 60s 冷却），客户端的自动提交口径随行。 */
        const val PHONE_LENGTH = 11
        const val CODE_LENGTH = 6

        /** The recovered `postDelayed(..., 100L)` before the IME is asked for. */
        const val SHOW_IME_DELAY_MILLIS = 100L

        /** What the original reported for the send tap; the analytics sink is a separate card. */
        const val SEND_CODE_LABEL = "发送验证码"
    }
}
