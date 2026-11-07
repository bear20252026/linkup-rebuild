package com.youloft.watcher.pages.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.youloft.watcher.R
import androidx.lifecycle.lifecycleScope
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.ActivityMainBinding
import com.youloft.watcher.pages.home.MapFragment
import com.youloft.watcher.pages.mine.MineFragment
import com.youloft.watcher.pages.more.MoreFragment
import com.youloft.watcher.pages.msg.MsgListFragment

/**
 * 主框架，照搬 `pages/main/MainActivity.java`：四个硬编码 Tab 名（"守护"/"消息中心"/"更多"/"我的"，
 * 原版就是字面量不是资源），ViewPager2 关手势切换（`setUserInputEnabled(false)`），选中态用
 * `G0(tag)` 里的 lottie 换 icon。入口有两条：登录后 `openMain()`、Splash 的 token 分支。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = TABS.size

            override fun createFragment(position: Int): Fragment = when (TABS[position]) {
                TAB_GUARD -> MapFragment()
                TAB_MESSAGE -> MsgListFragment()
                TAB_MORE -> MoreFragment()
                else -> MineFragment()
            }
        }
        binding.viewPager.isUserInputEnabled = false
        binding.rlGuard.setOnClickListener { switchTo(TAB_GUARD) }
        binding.layoutIm.setOnClickListener { switchTo(TAB_MESSAGE) }
        binding.rlMore.setOnClickListener { switchTo(TAB_MORE) }
        binding.layoutMine.setOnClickListener { switchTo(TAB_MINE) }
        switchTo(intent.getStringExtra(EXTRA_TAG) ?: TAB_GUARD)
        receiveLockCommands()
    }

    /**
     * 锁机命令收端（原版 WindowHelper 的应用内等价物）：IM `lockMachine` 到达 → 全屏 LockedActivity。
     * WindowManager 悬浮窗（盖在别的 app 上）需要 SYSTEM_ALERT_WINDOW 授权，授权卡落地前这里是
     * 应用内锁——DESIGN 已记录偏离。
     */
    private fun receiveLockCommands() {
        val graph = appGraph(this)
        com.youloft.watcher.data.im.LockCommandBridge(
            imClient = graph.imClient,
            api = graph.imApi,
            scope = lifecycleScope,
            onLock = { screen ->
                if (screen.userid > 0) {
                    com.youloft.watcher.pages.lock.LockedActivity.start(this, screen)
                }
            },
        ).start()
    }

    /** `G0(String)` — lottie plays over the selected tab's icon; the pager moves without smooth scroll. */
    private fun switchTo(tag: String) {
        val index = TABS.indexOf(tag).takeIf { it >= 0 } ?: 0
        val selected = TABS[index]
        listOf(
            binding.tabGuardAnima to binding.ivGuard,
            binding.tabImAnima to binding.ivIm,
            binding.tabMoreAnima to binding.ivMore,
            binding.tabMineAnima to binding.ivMine,
        ).forEach { (anima, icon) ->
            val isActive = anima.tag == selected
            anima.visibility = if (isActive) android.view.View.VISIBLE else android.view.View.INVISIBLE
            if (isActive) {
                anima.playAnimation()
            } else {
                anima.cancelAnimation()
            }
            icon.visibility = if (isActive) android.view.View.INVISIBLE else android.view.View.VISIBLE
        }
        binding.viewPager.setCurrentItem(index, false)
    }

    companion object {
        const val TAB_GUARD = "守护"
        const val TAB_MESSAGE = "消息中心"
        const val TAB_MORE = "更多"
        const val TAB_MINE = "我的"
        val TABS = listOf(TAB_GUARD, TAB_MESSAGE, TAB_MORE, TAB_MINE)
        private const val EXTRA_TAG = "tag"

        /** `MainActivity.a.b(context, tag, source)` — tag selects the landing tab. */
        fun start(context: Context, tag: String = TAB_GUARD) {
            context.startActivity(
                Intent(context, MainActivity::class.java).putExtra(EXTRA_TAG, tag),
            )
        }
    }
}
