package com.youloft.watcher.pages.track

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.ActivityTrackRecordListBinding
import com.youloft.watcher.databinding.ItemTrackRecordDateTabBinding
import kotlinx.coroutines.launch
import java.time.LocalDate

class TrackRecordActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTrackRecordListBinding
    private lateinit var viewModel: TrackRecordViewModel
    private var userId = 0L
    private val dateTabs = linkedMapOf<String, ItemTrackRecordDateTabBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrackRecordListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureSheet()
        val graph = appGraph(this)
        userId = intent.getLongExtra(EXTRA_QUERY_ID, 0L).takeIf { it > 0 } ?: graph.users.current().userId
        viewModel = ViewModelProvider(
            this,
            TrackRecordViewModel.factory(graph.trackRepository, TrackRowBuilder(TrackFormatter(applicationContext))),
        )[TrackRecordViewModel::class.java]
        binding.trackBack.setOnClickListener { finish() }
        binding.trackRefresh.setOnClickListener {
            viewModel.loadDays(userId)
            viewModel.load(userId, viewModel.state.value.selectedDate ?: LocalDate.now().toString())
        }
        if (userId <= 0) {
            binding.trackRefresh.isEnabled = false
            binding.trackMessage.visibility = View.VISIBLE
            binding.trackMessage.setText(R.string.track_list_login_required)
            return
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.track_list_container, TrackRecordFragment())
                .commit()
        }
        if (viewModel.state.value.selectedDate == null) {
            viewModel.loadDays(userId)
            viewModel.load(userId, savedInstanceState?.getString(STATE_DATE) ?: LocalDate.now().toString())
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderDates(state)
                    binding.trackLoading.visibility = if (state.loading) View.VISIBLE else View.GONE
                    val message = listOfNotNull(state.message, state.daysMessage).joinToString("\n")
                    binding.trackMessage.text = message
                    binding.trackMessage.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun configureSheet() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = getColor(R.color.colorCard)
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        val sheet = binding.trackSheet
        val behavior = BottomSheetBehavior.from(sheet)
        behavior.isGestureInsetBottomIgnored = true
        var sized = false
        sheet.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = dp(32).toFloat()
                // Extend the bottom corners beyond the clip so only the original top corners round.
                outline.setRoundRect(0, 0, view.width, view.height + radius.toInt(), radius)
            }
        }
        sheet.clipToOutline = true
        binding.trackCoordinator.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val height = binding.trackCoordinator.height
            if (height > 0 && bottom - top != oldBottom - oldTop) {
                behavior.peekHeight = minOf(dp(123), height)
                behavior.halfExpandedRatio = (dp(383).toFloat() / height).coerceIn(0.01f, 0.99f)
                val target = when {
                    !sized -> if (height <= dp(383)) BottomSheetBehavior.STATE_EXPANDED else BottomSheetBehavior.STATE_HALF_EXPANDED
                    height <= dp(383) -> BottomSheetBehavior.STATE_EXPANDED
                    else -> behavior.state
                }
                sized = true
                sheet.post { behavior.state = target }
            }
            renderSheet()
        }
        sheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> renderSheet() }
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, slideOffset: Float) = renderSheet()
            override fun onStateChanged(bottomSheet: View, newState: Int) = renderSheet()
        })
    }

    private fun renderSheet() {
        val height = binding.trackCoordinator.height
        val top = binding.trackSheet.top
        val halfTop = (height - dp(383)).coerceAtLeast(0)
        val collapsedTop = height - minOf(dp(123), height)
        val progress = when {
            halfTop > 0 -> (1f - top.toFloat() / halfTop).coerceIn(0f, 1f)
            collapsedTop > 0 -> ((collapsedTop - top).toFloat() / collapsedTop).coerceIn(0f, 1f)
            else -> 1f
        }
        binding.trackTitle.alpha = progress
        binding.trackMapMask.alpha = progress
        binding.trackTabBackground.alpha = 1f - progress
        binding.trackSheetIndicator.alpha = 1f - progress
        binding.trackResetCard.translationY = (top - dp(52)).coerceAtLeast(0).toFloat()
        binding.trackResetCard.alpha = 1f - progress
        binding.trackResetCard.visibility = if (progress == 1f) View.INVISIBLE else View.VISIBLE
        binding.trackMapHint.translationY = ((top - binding.trackMapHint.height) / 2f).coerceAtLeast(0f)
        binding.trackMapHint.alpha = 1f - progress
        binding.trackMapHint.visibility = if (progress == 1f) View.INVISIBLE else View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun renderDates(state: TrackUiState) {
        if (dateTabs.keys.toList() != state.dates) {
            binding.trackDates.removeAllViews()
            dateTabs.clear()
            state.dates.forEach { date ->
                val tab = ItemTrackRecordDateTabBinding.inflate(layoutInflater, binding.trackDates, false)
                val day = LocalDate.parse(date)
                tab.tvWeek.text = arrayOf("一", "二", "三", "四", "五", "六", "日")[day.dayOfWeek.value - 1]
                tab.tvDate.text = day.dayOfMonth.toString()
                tab.root.contentDescription = date
                tab.root.isFocusable = true
                tab.root.setOnClickListener { viewModel.load(userId, date) }
                binding.trackDates.addView(tab.root)
                dateTabs[date] = tab
            }
        }
        dateTabs.forEach { (date, tab) ->
            val selected = date == state.selectedDate
            val changed = tab.root.isSelected != selected
            tab.root.isSelected = selected
            val background = when {
                selected -> R.drawable.shape_track_date_selected
                date == LocalDate.now().toString() -> R.drawable.shape_track_date_today
                else -> 0
            }
            tab.tvDate.setBackgroundResource(background)
            tab.viewDot.visibility = if (date in state.recordedDates) View.VISIBLE else View.INVISIBLE
            if (selected && changed) {
                binding.trackDateScroll.post {
                    binding.trackDateScroll.smoothScrollTo(
                        tab.root.left - (binding.trackDateScroll.width - tab.root.width) / 2, 0,
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_DATE, viewModel.state.value.selectedDate)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_QUERY_ID = "queryId"
        private const val STATE_DATE = "track.selectedDate"

        fun start(context: Context, userId: Long) {
            context.startActivity(Intent(context, TrackRecordActivity::class.java).putExtra(EXTRA_QUERY_ID, userId))
        }
    }
}
