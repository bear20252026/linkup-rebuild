package com.youloft.watcher.pages.track

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.ActivityTrackRecordListBinding
import com.youloft.watcher.databinding.ItemTrackRecordDateTabBinding
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// This recovery host deliberately does not impersonate the original map, VIP gate or draggable sheet.
class TrackRecordActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTrackRecordListBinding
    private lateinit var viewModel: TrackRecordViewModel
    private var userId = 0L
    private val dateTabs = linkedMapOf<String, ItemTrackRecordDateTabBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrackRecordListBinding.inflate(layoutInflater)
        setContentView(binding.root)
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

    private fun renderDates(state: TrackUiState) {
        if (dateTabs.keys.toList() != state.dates) {
            binding.trackDates.removeAllViews()
            dateTabs.clear()
            state.dates.forEachIndexed { index, date ->
                val tab = ItemTrackRecordDateTabBinding.inflate(layoutInflater, binding.trackDates, false)
                val day = LocalDate.parse(date)
                tab.tvWeek.text = when (index) {
                    0 -> "今天"
                    1 -> getString(R.string.yesterday)
                    else -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)
                }
                tab.tvDate.text = day.format(DateTimeFormatter.ofPattern("M-d"))
                tab.root.contentDescription = date
                tab.root.setOnClickListener { viewModel.load(userId, date) }
                binding.trackDates.addView(tab.root)
                dateTabs[date] = tab
            }
        }
        dateTabs.forEach { (date, tab) ->
            val selected = date == state.selectedDate
            tab.root.isSelected = selected
            tab.tvDate.setTextColor(ContextCompat.getColor(this, if (selected) R.color.colorMale else R.color.appPrimary))
            tab.viewDot.visibility = if (date in state.recordedDates) View.VISIBLE else View.INVISIBLE
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
