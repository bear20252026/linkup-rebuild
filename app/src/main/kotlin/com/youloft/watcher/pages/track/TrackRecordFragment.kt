package com.youloft.watcher.pages.track

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.FragmentTrackRecordBinding
import com.youloft.watcher.pages.track.dialog.NoTrackReasonDialog
import kotlinx.coroutines.launch

class TrackRecordFragment : Fragment() {
    private var binding: FragmentTrackRecordBinding? = null
    private var reasonDialog: NoTrackReasonDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View =
        FragmentTrackRecordBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val views = requireNotNull(binding)
        val formatter = TrackFormatter(requireContext().applicationContext)
        val viewModel = ViewModelProvider(
            requireActivity(),
            TrackRecordViewModel.factory(appGraph(requireContext()).trackRepository, TrackRowBuilder(formatter)),
        )[TrackRecordViewModel::class.java]
        val summaryAdapter = TrackSummaryAdapter(mutableListOf(), formatter)
        val pointAdapter = TrackPointAdapter(
            items = mutableListOf(),
            onSelected = viewModel::select,
            onCleared = viewModel::clearSelection,
            onNoNewLocationClicked = {
                reasonDialog?.dismiss()
                reasonDialog = NoTrackReasonDialog(requireContext()).also { it.show() }
            },
        )
        views.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        views.recyclerView.adapter = ConcatAdapter(summaryAdapter, pointAdapter)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    val summaries = state.rows.filterIsInstance<TrackRow.Summary>()
                    val points = state.rows.filterIsInstance<TrackRow.Point>()
                    if (summaryAdapter.items != summaries) summaryAdapter.submitList(summaries)
                    if (pointAdapter.items != points) pointAdapter.submitList(points)
                    pointAdapter.selectedTimePeriod = state.selectedTimePeriod
                    views.emptyLayout.visibility = if (state.emptyVisible) View.VISIBLE else View.GONE
                    views.recyclerView.visibility = if (state.emptyVisible || state.loading) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        reasonDialog?.dismiss()
        reasonDialog = null
        binding?.recyclerView?.adapter = null
        binding = null
        super.onDestroyView()
    }
}
