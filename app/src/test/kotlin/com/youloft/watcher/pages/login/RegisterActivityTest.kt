package com.youloft.watcher.pages.login

import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import com.youloft.linkup.api.dto.User
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.data.local.UserCache
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * 注册页（程序化 UI，此前零测试）的三条主路：成功→存会话→进主界面；服务端拒绝→toast 可重试；
 * 会话写失败→**不进主界面**（登录不闪退，审计⑤）。实时查重走 [LoginFixtures.repository] 的记录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LoginFixtures.TestApplication::class)
class RegisterActivityTest {

    @Before
    fun setUp() {
        LoginFixtures.reset()
    }

    @Test
    fun `a successful registration stores the session and opens the main screen`() {
        LoginFixtures.repository.registerResponse =
            ApiResponse(200, "success", data = User(userId = 9L, nickname = "新用户", token = "tk-9"))
        val activity = launch()
        fill(activity, username = "新名字", password = "密码123", invite = "INV-1")

        submit(activity)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            listOf(LoginFixtures.FakeLoginRepository.RegisterAttempt("新名字", "密码123", "INV-1")),
            LoginFixtures.repository.registerCalls,
        )
        assertEquals("tk-9", LoginFixtures.stores.shared.string(UserCache.KEY_TOKEN), "the session landed before navigating")
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull(intent)
        assertEquals("com.youloft.watcher.pages.main.MainActivity", intent.component?.className)
    }

    @Test
    fun `a rejected registration toasts the reason and lets the user retry`() {
        LoginFixtures.repository.registerResponse = ApiResponse<User>(403, "邀请码已用尽")
        val activity = launch()
        fill(activity, username = "新名字", password = "密码123", invite = "SPENT")

        submit(activity)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            ShadowToast.getTextOfLatestToast()?.toString()?.contains("邀请码已用尽") == true,
            "the client toasts msg verbatim",
        )
        assertNull(shadowOf(activity).nextStartedActivity, "a rejection never opens the main screen")

        submit(activity)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, LoginFixtures.repository.registerCalls.size, "the rejection reset the in-flight guard")
    }

    @Test
    fun `a session that cannot be stored stays on the register page`() {
        LoginFixtures.repository.registerResponse =
            ApiResponse(200, "success", data = User(userId = 9L, nickname = "新用户", token = "tk-9"))
        LoginFixtures.stores = LoginFixtures.ThrowingStores()
        val activity = launch()
        fill(activity, username = "新名字", password = "密码123", invite = "INV-1")

        submit(activity)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            ShadowToast.getTextOfLatestToast()?.toString()?.contains("会话初始化异常") == true,
            "the honest answer for a session that did not land",
        )
        assertNull(shadowOf(activity).nextStartedActivity, "no token means no main screen")
        assertEquals(1, LoginFixtures.repository.registerCalls.size, "the account was created once, server-side")
    }

    @Test
    fun `typing a username checks its availability after the debounce`() {
        val activity = launch()
        fill(activity, username = "新名字")

        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        // 400ms debounce 是一个 postDelayed：快进 Robolectric 的时钟把它跑完。
        looper.runToEndOfTasks()

        assertEquals(listOf("新名字"), LoginFixtures.repository.usernameChecks)
    }

    // ------------------------------------------------------------------ fixtures

    private fun launch(): com.youloft.watcher.pages.login.RegisterActivity =
        Robolectric.buildActivity(com.youloft.watcher.pages.login.RegisterActivity::class.java).setup().get()

    /** 程序化布局没有 id：content → ScrollView → LinearLayout，按位取控件。 */
    private fun columnOf(activity: com.youloft.watcher.pages.login.RegisterActivity): LinearLayout {
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        return (content.getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
    }

    private fun fill(
        activity: com.youloft.watcher.pages.login.RegisterActivity,
        username: String? = null,
        password: String? = null,
        invite: String? = null,
    ) {
        val column = columnOf(activity)
        if (username != null) (column.getChildAt(1) as EditText).setText(username)
        if (password != null) (column.getChildAt(3) as EditText).setText(password)
        if (invite != null) (column.getChildAt(4) as EditText).setText(invite)
    }

    private fun submit(activity: com.youloft.watcher.pages.login.RegisterActivity) {
        (columnOf(activity).getChildAt(5) as Button).performClick()
    }
}
