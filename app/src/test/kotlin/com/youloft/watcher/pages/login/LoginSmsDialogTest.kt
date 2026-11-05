package com.youloft.watcher.pages.login

import android.app.Activity
import android.os.Looper
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.youloft.linkup.api.dto.User
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.R
import com.youloft.watcher.data.LoginRepository
import com.youloft.watcher.data.local.LoginFlags
import com.youloft.watcher.pages.login.LoginFixtures.FakeLoginRepository
import com.youloft.watcher.pages.login.LoginFixtures.RecordingLoginNavigation
import com.youloft.watcher.pages.login.LoginFixtures.TestApplication
import com.youloft.watcher.pages.login.dialog.LoginSmsDialog
import com.youloft.watcher.view.SendCodeButton
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The recovered SMS sheet, pinned: which lengths unlock what, the auto-submit, the countdown's lock on
 * the phone field, and the routing a successful login performs — including the `lastLoginType = 6` the
 * login page later reads back as the "上次登录" badge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class LoginSmsDialogTest {

    private val repository: FakeLoginRepository get() = LoginFixtures.repository
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var host: Activity
    private lateinit var navigation: RecordingLoginNavigation
    private lateinit var viewModel: LoginSmsViewModel

    @Before
    fun setUp() {
        LoginFixtures.reset()
        host = Robolectric.buildActivity(Activity::class.java).setup().get()
        navigation = RecordingLoginNavigation()
        viewModel = LoginSmsViewModel(repository)
    }

    private fun open(matchCode: Long? = null): LoginSmsDialog {
        val dialog = LoginSmsDialog(host, viewModel, matchCode)
        dialog.navigation = navigation
        dialog.show()
        idle()
        return dialog
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    private fun <T : android.view.View> view(dialog: LoginSmsDialog, id: Int): T = dialog.findViewById(id)

    private fun typePhone(dialog: LoginSmsDialog, text: String) {
        view<EditText>(dialog, R.id.et_phone).setText(text)
        idle()
    }

    private fun typeCode(dialog: LoginSmsDialog, text: String) {
        view<EditText>(dialog, R.id.et_code).setText(text)
        idle()
    }

    @Test
    fun `eleven digits unlock the send button and four more unlock login`() {
        val dialog = open()

        typePhone(dialog, "1380013800")
        assertFalse(view<TextView>(dialog, R.id.tv_send_code).isEnabled)
        assertFalse(view<android.view.View>(dialog, R.id.tv_login).isEnabled)

        typePhone(dialog, "13800138000")
        assertTrue(view<TextView>(dialog, R.id.tv_send_code).isEnabled)
        assertFalse(view<android.view.View>(dialog, R.id.tv_login).isEnabled, "a code is still missing")

        typeCode(dialog, "1234")
        assertTrue(view<android.view.View>(dialog, R.id.tv_login).isEnabled)
    }

    @Test
    fun `filling both fields submits without pressing login`() {
        val dialog = open()

        typePhone(dialog, "13800138000")
        typeCode(dialog, "1234")

        val sent = repository.phoneLogins
        assertEquals(1, sent.size, "the code watcher auto-submits once 11 and 4 are met")
        assertEquals(LoginRepository.TYPE_ID_SMS, sent.first().typeId)
        assertEquals("13800138000", sent.first().phone)
        assertEquals("1234", sent.first().smsCode)
        assertEquals("+86", sent.first().region)
        assertFalse(sent.first().bindingPhone, "this is a login, not a bind")
    }

    @Test
    fun `the send carries the number and the recovered country code`() {
        val dialog = open()

        // The field itself caps at 11 digits, so spaces cannot be typed into it — the trim the recovered
        // click handler applies is defensive, and the region is the literal "+86".
        typePhone(dialog, "13800138000")
        view<TextView>(dialog, R.id.tv_send_code).performClick()
        idle()

        assertEquals(listOf("13800138000" to "+86"), repository.sentPhones)
    }

    @Test
    fun `the delete cross clears the number and hides when the field is empty again`() {
        val dialog = open()
        typePhone(dialog, "13800138000")
        assertEquals(android.view.View.VISIBLE, view<ImageView>(dialog, R.id.iv_delete).visibility)

        view<ImageView>(dialog, R.id.iv_delete).performClick()
        idle()

        assertEquals("", view<EditText>(dialog, R.id.et_phone).text.toString())
        assertEquals(android.view.View.GONE, view<ImageView>(dialog, R.id.iv_delete).visibility)
    }

    @Test
    fun `a refused code request says why and leaves the phone field editable`() {
        repository.codeSent = ApiResponse(500, "发送太快了")
        val dialog = open()

        typePhone(dialog, "13800138000")
        view<TextView>(dialog, R.id.tv_send_code).performClick()
        idle()

        assertEquals("发送太快了", ShadowToast.getTextOfLatestToast()?.toString())
        assertTrue(view<EditText>(dialog, R.id.et_phone).isEnabled, "no countdown, so no lock")
    }

    @Test
    fun `an accepted code request moves focus to the code field and locks the number`() {
        val dialog = open()

        typePhone(dialog, "13800138000")
        view<TextView>(dialog, R.id.tv_send_code).performClick()
        idle()

        assertFalse(view<EditText>(dialog, R.id.et_phone).isEnabled)
        assertEquals(android.view.View.INVISIBLE, view<ImageView>(dialog, R.id.iv_delete).visibility)
        assertTrue(view<EditText>(dialog, R.id.et_code).hasFocus())
    }

    @Test
    fun `the countdown locks the number while it runs and releases it when the window ends`() {
        val dialog = open()

        // `CountDownTimer` messages never reach Robolectric, so the button is handed a ticker the test can
        // finish — installed before the send, while the button is still idle.
        val ticker = ManualTicker()
        view<SendCodeButton>(dialog, R.id.tv_send_code).let { target ->
            target.countdownFactory = { ticker }
            target.stopCountdown()
        }

        typePhone(dialog, "13800138000")
        view<TextView>(dialog, R.id.tv_send_code).performClick()
        idle()
        assertTrue(ticker.started, "the send ran through the test ticker")
        idle()

        assertFalse(view<EditText>(dialog, R.id.et_phone).isEnabled, "locked the moment the code went out")
        assertEquals(android.view.View.INVISIBLE, view<ImageView>(dialog, R.id.iv_delete).visibility)

        ticker.tick(10_000L)
        idle()
        assertEquals("重新发送（10）", view<SendCodeButton>(dialog, R.id.tv_send_code).text.toString())

        ticker.end()
        idle()

        assertTrue(view<EditText>(dialog, R.id.et_phone).isEnabled)
        assertEquals("重新发送", view<SendCodeButton>(dialog, R.id.tv_send_code).text.toString())
        assertEquals(android.view.View.VISIBLE, view<ImageView>(dialog, R.id.iv_delete).visibility)
    }

    /** Stands in for the platform countdown; see [com.youloft.watcher.view.SendCodeButtonTest]. */
    private class ManualTicker : SendCodeButton.Countdown {
        private var onTick: ((Long) -> Unit)? = null
        private var onFinish: (() -> Unit)? = null

        var started = false
            private set

        override fun start(onTick: (Long) -> Unit, onFinish: () -> Unit) {
            started = true
            this.onTick = onTick
            this.onFinish = onFinish
        }

        override fun cancel() {
            onTick = null
            onFinish = null
        }

        fun tick(millisLeft: Long) = onTick?.invoke(millisLeft)

        fun end() = onFinish?.invoke()
    }

    @Test
    fun `a new account is routed to profile completion, carrying the H5 code through`() {
        repository.login = ApiResponse(
            200,
            "success",
            data = User(userId = 7L, nickname = "新注册", token = "tk", isRegister = true),
        )
        val dialog = open(matchCode = 424_242L)

        typePhone(dialog, "13800138000")
        typeCode(dialog, "1234")

        assertEquals(listOf("improve(avatar=,nickname=,fromH5=true,match=424242)"), navigation.calls)
    }

    @Test
    fun `an existing account goes straight to the main screen`() {
        repository.login = ApiResponse(
            200,
            "success",
            data = User(userId = 7L, nickname = "老朋友", token = "tk", isRegister = false),
        )
        val dialog = open()

        typePhone(dialog, "13800138000")
        typeCode(dialog, "1234")

        assertEquals(listOf("main"), navigation.calls)
    }

    @Test
    fun `a successful login records the phone type and caches the account`() {
        repository.login = ApiResponse(
            200,
            "success",
            data = User(userId = 7L, nickname = "老朋友", token = "tk", isRegister = false),
        )
        val dialog = open()

        typePhone(dialog, "13800138000")
        typeCode(dialog, "1234")

        assertEquals(
            LoginFlags.LOGIN_TYPE_PHONE,
            LoginFixtures.stores.shared.int(LoginFlags.KEY_LAST_LOGIN_TYPE),
            "the sheet, not the repository, is what records 手机号 as the last type",
        )
        assertFalse(dialog.isShowing, "the sheet closes itself on the way out")
        idle()
        assertTrue(host.isFinishing, "and the screen behind it is finished")
    }

    @Test
    fun `a rejected login reports the server's words and keeps the sheet open`() {
        repository.login = ApiResponse(500, "验证码错误", data = null)
        val dialog = open()

        typePhone(dialog, "13800138000")
        typeCode(dialog, "1234")

        assertEquals("验证码错误", ShadowToast.getTextOfLatestToast()?.toString())
        assertTrue(dialog.isShowing)
        assertFalse(host.isFinishing)
        assertEquals(0, LoginFixtures.stores.shared.int(LoginFlags.KEY_LAST_LOGIN_TYPE))
    }

    @Test
    fun `the caller's callback runs before the routing decision`() {
        val order = mutableListOf<String>()
        repository.login = ApiResponse(
            200,
            "success",
            data = User(userId = 7L, nickname = "老朋友", token = "tk", isRegister = false),
        )
        val dialog = LoginSmsDialog(host, viewModel, null) { order += "callback" }
        dialog.navigation = object : LoginNavigation {
            override fun openImproveUserInfo(avatar: String, nickname: String, fromH5: Boolean, matchCode: Long) {
                order += "improve"
            }

            override fun openMain() {
                order += "main"
            }

            override fun openPrivacyPolicy() = Unit
            override fun openUserAgreement() = Unit
            override fun openCarrierPolicy(url: String, title: String) = Unit
        }
        dialog.show()
        idle()

        typePhone(dialog, "13800138000")
        typeCode(dialog, "1234")

        assertEquals(listOf("callback", "main"), order)
    }
}
