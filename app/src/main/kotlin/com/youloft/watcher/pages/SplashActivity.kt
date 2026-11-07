package com.youloft.watcher.pages

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieAnimationView
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.data.local.PrivacyGate
import com.youloft.watcher.databinding.ActivitySplashBinding
import com.youloft.watcher.pages.guide.GuideActivity
import com.youloft.watcher.pages.login.EmptyLoginActivity
import com.youloft.watcher.pages.main.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 启动分流，照搬 `_raw/out_jadx_156/sources/com/youloft/watcher/pages/SplashActivity.java` 的
 * `M()` + `t0()` 决策链，去掉的是广告与热启动两条外部分支（广告 SDK 已随源码丢失，此 build 无从
 * 触达；`hotLaunch` 在无广告场景只意味着"动画照播、直进下一屏"）：
 *
 * 1. 非 task root 且无深链 → 直接 finish（去重热启动）。
 * 2. **未同意隐私 → EmptyLoginActivity**（隐私门本身在登录页上，这是原包结构，不是本工程的发明）。
 * 3. 已同意：播一次启动动画后按 token 分流 —— `userId > 0` → MainActivity；无 token → 引导页
 *    （`CacheUtils.v() >= 4` 的"已看过引导"标记，本工程用 `guideSeenVersion` 键）或登录页。
 * 4. 应用锁（`CacheUtils.e()` 非空 → AppPswVerify）属应用锁卡，未重建前该分支不存在。
 * 5. 深链（`match` scheme）与 ImproveUserInfo 待资料补全卡，本 round 均为记录不路由。
 */
class SplashActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // M(): a hot launch that carries nothing is not a real start — the original finished here too.
        if (!isTaskRoot && intent.extras == null && intent.data == null) {
            finish()
            return
        }
        playSplashOnce { route() }
    }

    /**
     * `t0()` 的完整决策链（含隐私门第一分支）；可见性 internal 是给分流测试的——测试直接驱动
     * route()，跳过动画等待。隐私分支放在这里而不是 onCreate，才能保证"动画结束前用户看到的是
     * 启动页，结束后才被隐私门拦下"，与原版的时序一致。
     */
    internal fun route() {
        val graph = appGraph(this)
        if (!graph.privacyGate.agreed()) {
            EmptyLoginActivity.start(this)
            finish()
            return
        }
        if (graph.users.current().userId > 0) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        if (graph.loginFlags.guideSeenVersion() >= GUIDE_SEEN_VERSION) {
            startActivity(Intent(this, com.youloft.watcher.pages.login.LoginActivity::class.java))
        } else {
            startActivity(Intent(this, GuideActivity::class.java))
        }
        finish()
    }

    private fun playSplashOnce(then: () -> Unit) {
        val lottie: LottieAnimationView = binding.lottieAppName
        lifecycleScope.launch {
            // The original ran two 250ms corner-image fades around this lottie; the animation itself
            // is the timing budget a no-ad build keeps.
            lottie.playAnimation()
            delay(1_200)
            then()
        }
    }

    companion object {
        /** `CacheUtils.H1(4)` — the version the guide marks itself seen with. */
        const val GUIDE_SEEN_VERSION = 4
    }
}
