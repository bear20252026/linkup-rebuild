package com.youloft.watcher.pages.login

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.text.Spannable
import android.text.style.ClickableSpan
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.youloft.linkup.api.dto.Cut
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.R
import com.youloft.watcher.data.local.LoginFlags
import com.youloft.watcher.pages.login.LoginFixtures.FakeLoginRepository
import com.youloft.watcher.pages.login.LoginFixtures.RecordingLoginDialogs
import com.youloft.watcher.pages.login.LoginFixtures.RecordingLoginNavigation
import com.youloft.watcher.pages.login.LoginFixtures.RecordingThirdPartyAuth
import com.youloft.watcher.pages.login.LoginFixtures.TestApplication
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression net for the rebuilt login screen: which one-tap vendor the cut answer picks, what makes the
 * screen choose the SMS sheet instead, the agreement checkbox in front of every button, the "上次登录"
 * badges, and the vendor-install branches. The SDKs and the two sheets are seams, so these tests pin the
 * decisions the recovered tree made — not any vendor behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class LoginActivityTest {

    private val repository: FakeLoginRepository get() = LoginFixtures.repository
    private val oneTap get() = LoginFixtures.oneTap
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private val dialogs = RecordingLoginDialogs()
    private val navigation = RecordingLoginNavigation()
    private val platform = RecordingThirdPartyAuth()

    @Before
    fun setUp() {
        LoginFixtures.reset()
        dialogs.calls.clear()
        navigation.calls.clear()
        platform.calls.clear()
    }

    private fun launch(intent: Intent = Intent(context, LoginActivity::class.java)): LoginActivity {
        val controller = Robolectric.buildActivity(LoginActivity::class.java, intent)
        controller.get().dialogs = dialogs
        controller.get().navigation = navigation
        controller.get().platform = platform
        controller.get().oneTap = LoginFixtures.oneTap
        controller.setup()
        idle()
        return controller.get()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Every click here is debounced at 500ms, so a second press needs the clock to move first. */
    private fun pastDebounce() {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600L))
    }

    private fun <T : View> view(activity: LoginActivity, id: Int): T = activity.window.decorView.findViewById(id)

    private fun checkbox(activity: LoginActivity) = view<CheckBox>(activity, R.id.cb_read_privacy_policy)

    private fun agreement(activity: LoginActivity): CharSequence =
        view<TextView>(activity, R.id.tv_read_privacy_policy).text

    private fun spans(text: CharSequence): List<ClickableSpan> =
        assertNotNull(text as? Spannable, "the agreement line is a Spannable by construction")
            .getSpans(0, text.length, ClickableSpan::class.java)
            .toList()

    @Test
    fun `the screen pulls the cut once on create and the answer picks the vendor`() {
        repository.cut = ApiResponse(200, "success", data = Cut(loginType = "3"))

        launch()

        assertEquals(1, repository.cutCalls, "one pull, on create — the original never retried")
        assertEquals(listOf(3), oneTap.requested)
    }

    @Test
    fun `an unreadable or missing login type falls back to the first vendor`() {
        repository.cut = ApiResponse(200, "success", data = Cut(loginType = "unknown"))
        launch()
        assertEquals(listOf(LoginActivity.ONE_TAP_GY), oneTap.requested)

        LoginFixtures.reset()
        repository.cut = ApiResponse(500, "boom", data = null)
        launch()
        assertEquals(listOf(1), oneTap.requested, "the original read only the data, never the status")
    }

    @Test
    fun `a masked number with an operator opens the one-tap sheet, anything else the SMS one`() {
        oneTap.answer = OneTapAccount(number = "138****8000", operatorTypeStr = "中国移动")
        val activity = launch()
        checkbox(activity).isChecked = true
        view<View>(activity, R.id.iv_login_phone).performClick()
        assertEquals(listOf("oneTap(number=138****8000)"), dialogs.calls)

        LoginFixtures.reset()
        dialogs.calls.clear()
        oneTap.answer = OneTapAccount(number = "138****8000", operatorTypeStr = "")
        val second = launch()
        checkbox(second).isChecked = true
        view<View>(second, R.id.iv_login_phone).performClick()
        assertEquals(listOf("sms(match=0)"), dialogs.calls, "no operator means the SMS sheet")
    }

    @Test
    fun `the H5 match code travels into the sheet it opens`() {
        val intent = Intent(context, LoginActivity::class.java).apply {
            putExtra(LoginActivity.EXTRA_MATCH_CODE, 424_242L)
        }
        val activity = launch(intent)
        checkbox(activity).isChecked = true

        view<View>(activity, R.id.iv_login_phone).performClick()

        assertEquals(listOf("sms(match=424242)"), dialogs.calls)
    }

    @Test
    fun `the checkbox gates the phone button until the reminder is confirmed`() {
        val activity = launch()

        view<View>(activity, R.id.iv_login_phone).performClick()

        assertTrue(dialogs.calls.isEmpty(), "nothing may open while the box is off")
        val sheet = assertNotNull(ShadowDialog.getLatestDialog())
        assertTrue(sheet.isShowing)
        assertFalse(checkbox(activity).isChecked)

        sheet.findViewById<View>(R.id.tv_confirm).performClick()
        idle()

        assertTrue(checkbox(activity).isChecked, "confirming ticks the box for the user")
        assertEquals(listOf("sms(match=0)"), dialogs.calls, "and replays the press that was blocked")
    }

    @Test
    fun `the reminder sheet names the carrier terms when the vendor supplied them`() {
        oneTap.answer = OneTapAccount(
            number = "138****8000",
            operatorTypeStr = "中国移动",
            privacyName = "中国移动认证服务条款",
            privacyUrl = "https://example.test/terms",
        )
        val activity = launch()

        view<View>(activity, R.id.iv_login_phone).performClick()

        val content = assertNotNull(ShadowDialog.getLatestDialog())
            .findViewById<TextView>(R.id.tv_content).text.toString()
        assertTrue(content.contains("《中国移动认证服务条款》"), content)
        assertTrue(content.contains("《隐私政策》") && content.contains("《用户协议》"), content)
    }

    @Test
    fun `the leading phrase of the agreement line toggles the checkbox`() {
        val activity = launch()
        val text = agreement(activity)
        val lead = context.getString(R.string.i_read_and_agree)
        assertTrue(text.toString().startsWith(lead), text.toString())

        spans(text).first().onClick(checkbox(activity))
        assertTrue(checkbox(activity).isChecked)
        spans(activity.window.decorView.findViewById<TextView>(R.id.tv_read_privacy_policy).text)
            .first().onClick(checkbox(activity))
        assertFalse(checkbox(activity).isChecked, "it is a toggle, not a set")
    }

    @Test
    fun `each agreement link opens the web card it was wired to`() {
        val activity = launch()
        val anchor = view<View>(activity, R.id.tv_read_privacy_policy)

        spans(agreement(activity)).let { all ->
            all[1].onClick(anchor)
            all[2].onClick(anchor)
        }

        assertEquals(listOf("privacy", "agreement"), navigation.calls)
    }

    @Test
    fun `the carrier clause waits for a resume when the number came back empty`() {
        oneTap.answer = OneTapAccount(number = "")
        assertFalse(agreement(launch()).toString().contains("和《"))

        oneTap.answer = OneTapAccount(number = "", privacyName = "运营商条款", privacyUrl = "https://example.test/x")
        dialogs.calls.clear()
        navigation.calls.clear()
        val controller = Robolectric.buildActivity(LoginActivity::class.java)
        controller.get().dialogs = dialogs
        controller.get().navigation = navigation
        controller.get().platform = platform
        controller.get().oneTap = LoginFixtures.oneTap
        controller.setup()
        idle()

        // V0() is only reached from the pre-login branch when a number came back. The fields are still
        // assigned, and onResume repaints the line from them -- which is what this asserts.
        val activity = controller.get()
        assertFalse(agreement(activity).toString().contains("和《运营商条款》"))

        controller.pause().resume()
        idle()
        val text = agreement(activity)
        assertTrue(text.toString().contains("和《运营商条款》"), text.toString())

        spans(text).last().onClick(view(activity, R.id.tv_read_privacy_policy))
        assertEquals(listOf("carrier(url=https://example.test/x,title=运营商条款)"), navigation.calls)
    }

    @Test
    fun `wechat and qq presses stop at the install check`() {
        platform.wechatInstalled = false
        platform.qqInstalled = false
        val activity = launch()
        checkbox(activity).isChecked = true

        view<View>(activity, R.id.tv_login_wechat).performClick()
        view<View>(activity, R.id.iv_login_qq).performClick()
        idle()

        assertTrue(platform.calls.isEmpty())
        assertEquals(
            context.getString(R.string.please_install_qq_first),
            ShadowToast.getTextOfLatestToast()?.toString(),
            "the last refusal is the QQ one; the WeChat one came first",
        )
    }

    @Test
    fun `a direct qq press asks the vendor and leaves the button enabled`() {
        val activity = launch()
        checkbox(activity).isChecked = true

        view<View>(activity, R.id.iv_login_qq).performClick()
        idle()

        assertEquals(listOf("qqLogin"), platform.calls)
        assertTrue(view<View>(activity, R.id.iv_login_qq).isEnabled, "the direct press never disabled it")
    }

    fun `the same press through the reminder sheet does disable the qq button`() {
        val activity = launch()

        view<View>(activity, R.id.iv_login_qq).performClick()
        assertNotNull(ShadowDialog.getLatestDialog()).findViewById<View>(R.id.tv_confirm).performClick()
        idle()

        assertEquals(listOf("qqLogin"), platform.calls)
        assertFalse(view<View>(activity, R.id.iv_login_qq).isEnabled, "the reminder continuation did")
    }

    @Test
    fun `the customer-service entry needs wechat and then asks for the chat`() {
        val activity = launch()
        view<View>(activity, R.id.iv_cs).performClick()
        assertEquals(listOf("customerService"), platform.calls)

        platform.wechatInstalled = false
        platform.calls.clear()
        view<View>(activity, R.id.iv_cs).performClick()
        assertTrue(platform.calls.isEmpty())
    }

    @Test
    fun `the badges need the logout extra and only light for the recorded type`() {
        fun badges(stored: Int, logout: Boolean): List<Int> {
            LoginFixtures.reset()
            LoginFixtures.stores.shared.putInt(LoginFlags.KEY_LAST_LOGIN_TYPE, stored)
            val activity = launch(Intent(context, LoginActivity::class.java).apply {
                putExtra(LoginActivity.EXTRA_LOGOUT, logout)
            })
            return listOf(
                view<View>(activity, R.id.tv_last_login_wechat).visibility,
                view<View>(activity, R.id.tv_last_login_phone).visibility,
                view<View>(activity, R.id.tv_last_login_qq).visibility,
            )
        }

        assertEquals(listOf(View.VISIBLE, View.GONE, View.GONE), badges(4, logout = true))
        assertEquals(listOf(View.GONE, View.VISIBLE, View.GONE), badges(6, logout = true))
        assertEquals(listOf(View.GONE, View.GONE, View.VISIBLE), badges(5, logout = true))
        assertEquals(
            listOf(View.GONE, View.GONE, View.GONE),
            badges(6, logout = false),
            "the original only read the badge for a logout entry",
        )
        assertEquals(
            listOf(View.GONE, View.GONE, View.GONE),
            badges(3305, logout = true),
            "3305 is the channel the shipped APKs carry, and it matches no badge",
        )
    }

    @Test
    fun `the notification permission is asked once per account`() {
        launch()
        assertTrue(
            LoginFixtures.stores.forUser(0L).boolean(LoginFlags.KEY_REQUESTED_NOTIFICATION, false),
            "the marker is written before the request goes out, exactly like the original",
        )
    }

    @Test
    fun `start debounces repeat presses and carries the H5 pair`() {
        LoginActivity.start(context, fromH5 = true, matchCode = 424_242L, logout = true)
        LoginActivity.start(context, fromH5 = true, matchCode = 1L)

        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val started = assertNotNull(shadowOf(application).nextStartedActivity)
        assertTrue(started.getBooleanExtra(LoginActivity.EXTRA_FROM_H5, false))
        assertEquals(424_242L, started.getLongExtra(LoginActivity.EXTRA_MATCH_CODE, 0L))
        assertTrue(started.getBooleanExtra(LoginActivity.EXTRA_LOGOUT, false))
        assertEquals(LoginActivity.START_FLAGS, started.flags)
        assertNull(shadowOf(application).nextStartedActivity, "the 500ms gate swallows the second start")
    }
}
