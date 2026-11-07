package com.youloft.watcher.pages

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.youloft.watcher.StorelessApp
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.data.local.PrivacyGate
import com.youloft.watcher.pages.guide.GuideActivity
import com.youloft.watcher.pages.login.EmptyLoginActivity
import com.youloft.watcher.pages.login.LoginActivity
import com.youloft.watcher.pages.main.MainActivity
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Splash 的分流真值表：隐私门 → 引导/登录 → 主界面，与原版 `SplashActivity.M()/t0()` 的顺序一致
 * （广告与热启动两条外部分支不在本 build）。每个用例直接驱动 `route()`，跳过动画等待。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = StorelessApp::class)
class SplashActivityTest {

    private fun splash(): SplashActivity =
        Robolectric.buildActivity(SplashActivity::class.java).create().get()

    private fun nextStarted(activity: SplashActivity): Intent =
        shadowOf(activity).nextStartedActivity

    @Test
    fun `privacy refused lands on the gate, not the login`() {
        val activity = splash()
        activity.route()
        assertEquals(
            EmptyLoginActivity::class.java.name,
            nextStarted(activity).component?.className,
            "未同意隐私：进 EmptyLogin（隐私门在登录页上，原包结构）",
        )
    }

    @Test
    fun `agreed without account and unseen guide opens the guide`() {
        val activity = splash()
        appGraph(ApplicationProvider.getApplicationContext()).privacyGate.agree()
        activity.route()
        assertEquals(GuideActivity::class.java.name, nextStarted(activity).component?.className)
    }

    @Test
    fun `agreed without account and guide seen opens login`() {
        val activity = splash()
        appGraph(ApplicationProvider.getApplicationContext()).privacyGate.agree()
        appGraph(ApplicationProvider.getApplicationContext()).loginFlags.markGuideSeen(SplashActivity.GUIDE_SEEN_VERSION)
        activity.route()
        assertEquals(LoginActivity::class.java.name, nextStarted(activity).component?.className)
    }

    @Test
    fun `a signed-in account goes straight to the main tabs`() {
        val activity = splash()
        appGraph(ApplicationProvider.getApplicationContext()).privacyGate.agree()
        appGraph(ApplicationProvider.getApplicationContext()).users.store(user = com.youloft.linkup.api.dto.User(userId = 10000001L, nickname = "妈"))
        activity.route()
        assertEquals(MainActivity::class.java.name, nextStarted(activity).component?.className)
    }
}
