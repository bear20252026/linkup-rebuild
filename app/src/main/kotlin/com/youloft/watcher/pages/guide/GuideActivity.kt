package com.youloft.watcher.pages.guide

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.airbnb.lottie.LottieAnimationView
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.ActivityGuideBinding
import com.youloft.watcher.databinding.FragmentGuideBinding
import com.youloft.watcher.pages.login.LoginActivity

/**
 * 引导页，照搬 `pages/guide/GuideActivity.java` + `GuidePageData`：固定 4 页（实时位置/使用记录/
 * 报备提醒/锁机功能），每页 lottie 循环 + 描述图；跳过与最后一页的"开启LinkUp之旅"都写
 * `guideSeenVersion = 4` 后进登录。返回键在原版就被吞掉，这里同样。
 */
class GuideActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = GuidePageData.PAGES.size

            override fun createFragment(position: Int): Fragment =
                GuideFragment.newInstance(GuidePageData.PAGES[position])
        }
        binding.tvSkip.setOnClickListener { finishToLogin() }
        binding.llNext.setOnClickListener {
            val current = binding.viewPager.currentItem
            if (current == GuidePageData.PAGES.size - 1) {
                finishToLogin()
            } else {
                binding.viewPager.setCurrentItem(current + 1, true)
                binding.tvNext.setText(GuidePageData.PAGES[current + 1].titleRes)
            }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
    }

    private fun finishToLogin() {
        appGraph(this).loginFlags.markGuideSeen(GUIDE_SEEN_VERSION)
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    companion object {
        const val GUIDE_SEEN_VERSION = 4

        fun start(context: Context) {
            context.startActivity(Intent(context, GuideActivity::class.java))
        }
    }
}

/** `bean/GuidePageData` — the four shipped pages, same order, same colors. */
data class GuidePage(val id: Int, val titleRes: Int, val lottieFile: String, val startColorRes: Int) : java.io.Serializable

object GuidePageData {
    val PAGES = listOf(
        GuidePage(4, R.string.real_time_location, "guide/guide_location.json", R.color.color_cdfabc),
        GuidePage(2, R.string.usage_records, "guide/guide_usage.json", R.color.color_b2d6ff),
        GuidePage(3, R.string.report_reminder, "guide/guide_report.json", R.color.colorBrand),
        GuidePage(5, R.string.guide_lock_phone_title, "guide/guide_lock.json", R.color.color_deccfb),
    )
}

/** `pages/guide/GuideFragment` — one lottie page; `animate_view` plays 0.2..1.0 looping. */
class GuideFragment : Fragment() {
    private var _binding: FragmentGuideBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        FragmentGuideBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val page = requireArguments().getSerializable(ARG_PAGE) as GuidePage
        binding.animateView.setAnimation(page.lottieFile)
        binding.animateView.setMinAndMaxProgress(0.2f, 1.0f)
        binding.animateView.repeatCount = com.airbnb.lottie.LottieDrawable.INFINITE
        binding.animateView.playAnimation()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_PAGE = "page"

        fun newInstance(page: GuidePage): GuideFragment = GuideFragment().apply {
            arguments = Bundle().apply { putSerializable(ARG_PAGE, page) }
        }
    }
}
