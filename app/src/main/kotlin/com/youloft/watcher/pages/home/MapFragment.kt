package com.youloft.watcher.pages.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.data.local.GuardTarget
import com.youloft.watcher.databinding.FragmentMapBinding
import com.youloft.watcher.pages.friend.AddFriendActivity
import com.youloft.watcher.pages.friend.FriendRequestListActivity
import com.youloft.watcher.pages.friend.MyFriendActivity
import com.youloft.watcher.pages.home.dialog.SwitchFriendDialog
import com.youloft.watcher.pages.track.TrackRecordActivity
import kotlinx.coroutines.launch

/**
 * 守护 Tab，`pages/home/MapFragment.java`（1426 行）的本轮重建：
 *
 * - **地图槽**：目录里有已安装提供方就嵌入 `:map` 的真地图；没有就维持 `mapContainer` 上的
 *   "尚未接入"状态（本工程的地图卡规则：不画假底图）。
 * - **右上菜单** `MenuLayout`：好友卡点击开 `SwitchFriendDialog`（原版入口漏斗
 *   "守护Tab-好友切换弹窗"），VIP 卡按 `hadVipPlan` 加入。
 * - **添加好友 banner** 与离线提醒行照原样摆放；banner 点击 → 添加好友页。
 * - **拖动面板**：原版的 fastkit `SheetLayout` 未还原，用 BottomSheetBehavior 承载（轨迹页 #25 的
 *   同款先例），peek 123dp、拖过 0.8 折叠菜单（原版 0.8f 阈值）。
 * - **面板内容**：原版是服务端 HomeData 驱动的功能卡（通话记录/应用使用），该路由服务端未建——
 *   这里放真实可用的入口行（轨迹/好友/申请），如实标注。
 */
class MapFragment : Fragment() {
    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    private val guardTarget by lazy { GuardTarget(appGraph(requireContext()).keyValueStores) }
    private var switchDialog: SwitchFriendDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        FragmentMapBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        attachMapIfAvailable()
        binding.menuLayout.setOnFriendsClick { openSwitchDialog() }
        binding.menuLayout.showVipWhenEligible(appGraph(requireContext()).users.current())
        binding.addFriendsLayout.setOnClickListener {
            AddFriendActivity.start(requireContext(), source = 1)
        }
        binding.ivTitle.setOnClickListener { openSwitchDialog() }
        binding.llAbnormal.setOnClickListener { openSwitchDialog() }

        val sheet = binding.sheetLayout
        val behavior = BottomSheetBehavior.from(sheet)
        behavior.peekHeight = resources.displayMetrics.let { (123 * it.density).toInt() }
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                // The original folded the menu past 0.8 of its travel; slideOffset here is -1..1
                // (1 = expanded), so the fold-in happens near the top of the travel.
                binding.menuLayout.setOnlyItemFriendsVisible(slideOffset >= 0.5f)
            }

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                binding.menuLayout.setOnlyItemFriendsVisible(
                    newState == BottomSheetBehavior.STATE_EXPANDED,
                )
            }
        })

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = HomeEntryAdapter(
            listOf(
                HomeEntry(R.drawable.ic_home_location, R.string.real_time_location) {
                    TrackRecordActivity.start(requireContext(), guardTarget.current())
                },
                HomeEntry(R.drawable.ic_menu_permission_setup, R.string.switch_friends) {
                    openSwitchDialog()
                },
                HomeEntry(R.drawable.ic_main_tab_guard_unchecked, R.string.my_friends) {
                    MyFriendActivity.start(requireContext())
                },
                HomeEntry(R.drawable.ic_main_tab_more_unchecked, R.string.friend_request) {
                    startActivity(
                        android.content.Intent(requireContext(), FriendRequestListActivity::class.java),
                    )
                },
            ),
        )
        refreshGuardState()
    }

    override fun onResume() {
        super.onResume()
        refreshGuardState()
    }

    private fun refreshGuardState() {
        val target = guardTarget.current()
        binding.llAbnormal.visibility =
            if (target == 0L) View.VISIBLE else View.GONE
    }

    private fun openSwitchDialog() {
        if (switchDialog?.isShowing == true) return
        switchDialog = SwitchFriendDialog(requireContext()).also { it.show() }
    }

    /** The :map card's own rule: real vendor when installed, honest empty slot otherwise. */
    private fun attachMapIfAvailable() {
        val graph = appGraph(requireContext())
        val provider = graph.trackMaps.preferred() ?: return
        val map = provider.create(requireContext())
        binding.mapContainer.addView(
            map.view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        map.onCreate(null)
        map.showTrack(com.youloft.linkup.map.TrackOverlay(path = emptyList(), stays = emptyList()))
    }

    override fun onDestroyView() {
        switchDialog?.dismiss()
        switchDialog = null
        _binding = null
        super.onDestroyView()
    }
}

/** Sheet entry rows: the ones that route to rebuilt screens, with the icon the original card family used. */
data class HomeEntry(val iconRes: Int, val titleRes: Int, val onClick: () -> Unit)

class HomeEntryAdapter(private val entries: List<HomeEntry>) : RecyclerView.Adapter<HomeEntryAdapter.Holder>() {
    class Holder(val view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_entry, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        holder.view.findViewById<android.widget.ImageView>(R.id.iv_home_entry_icon)
            .setImageResource(entry.iconRes)
        holder.view.findViewById<android.widget.TextView>(R.id.tv_home_entry_title)
            .setText(entry.titleRes)
        holder.view.setOnClickListener { entry.onClick() }
    }
}
