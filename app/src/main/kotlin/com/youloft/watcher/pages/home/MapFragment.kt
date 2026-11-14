package com.youloft.watcher.pages.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.youloft.linkup.api.dto.HomeData
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.data.local.GuardTarget
import com.youloft.watcher.databinding.FragmentMapBinding
import com.youloft.watcher.pages.friend.AddFriendActivity
import com.youloft.watcher.pages.friend.FriendRequestListActivity
import com.youloft.watcher.pages.friend.MyFriendActivity
import com.youloft.watcher.pages.home.dialog.SwitchFriendDialog
import com.youloft.watcher.pages.lock.LockListActivity
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
 * - **面板内容**：服务端 `api/index` 聚合（friendState/friendLocation/lockInfo/phoneInfoCard）
 *   驱动的功能卡（待验 78 闭环）——四张已接线的卡在上，真实可用的入口行（轨迹/好友/申请）在
 *   下；通话记录/应用使用卡等报表路由供数前缺席（原卡空数据时也收起）。
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
        binding.recyclerView.adapter = HomePanelAdapter(entryRows(), ::onCardClick)
        refreshGuardState()
        loadHomeCards()
    }

    override fun onResume() {
        super.onResume()
        refreshGuardState()
        loadHomeCards()
    }

    private fun entryRows() = listOf(
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
    )

    /** 卡片路由：位置卡进轨迹页、锁卡进锁机列表；状态卡无已建二级屏（原版跳 H5 报表）。 */
    private fun onCardClick(kind: HomeCards.Kind) {
        when (kind) {
            HomeCards.Kind.LOCATION ->
                TrackRecordActivity.start(requireContext(), guardTarget.current())
            HomeCards.Kind.LOCK -> LockListActivity.start(requireContext())
            else -> Unit
        }
    }

    /** `api/index`（HomeManager 重建）：失败静默保留上一批卡。 */
    private fun loadHomeCards() {
        val adapter = binding.recyclerView.adapter as? HomePanelAdapter ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val response = runCatching { appGraph(requireContext()).imApi.index() }.getOrNull()
            val data: HomeData? = if (response?.isSuccess == true) response.data else null
            adapter.submitCards(
                HomeCards.fromData(data, cardTexts()) { durationSeconds, endEpochMillis ->
                    HomeCards.Remaining.format(durationSeconds, endEpochMillis)
                },
            )
        }
    }

    private fun cardTexts() = object : HomeCards.Texts {
        override val phoneStatus = getString(R.string.home_card_phone_status)
        override val charging = getString(R.string.home_card_charging)
        override val networkWifi = getString(R.string.home_card_network_wifi)
        override val networkCellular = getString(R.string.home_card_network_cellular)
        override val networkNone = getString(R.string.home_card_network_none)
        override val screenTime: (String) -> String = { getString(R.string.home_card_screen_time, it) }
        override val offline = getString(R.string.home_card_offline)
        override val location = getString(R.string.home_card_location)
        override val noAddress = getString(R.string.home_card_no_address)
        override val arrivedAt: (String) -> String = { getString(R.string.home_card_arrived_at, it) }
        override val callRecord = getString(R.string.home_card_call_record)
        override val callIncoming = getString(R.string.home_card_call_incoming)
        override val callOutgoing = getString(R.string.home_card_call_outgoing)
        override val callMissed = getString(R.string.home_card_call_missed)
        override val callSummary: (Int) -> String = { getString(R.string.home_card_call_summary, it) }
        override val unknownNumber = getString(R.string.home_card_unknown_number)
        override val appUse = getString(R.string.home_card_app_use)
        override val appUseToday: (Int) -> String = { getString(R.string.home_card_app_use_today, it) }
        override val appUseYesterday: (Int) -> String = { getString(R.string.home_card_app_use_yesterday, it) }
        override val unknownApp = getString(R.string.home_card_unknown_app)
        override val sensitive = getString(R.string.home_card_sensitive)
        override val sensitiveInstall = getString(R.string.home_card_sensitive_install)
        override val sensitiveUninstall = getString(R.string.home_card_sensitive_uninstall)
        override val sensitiveUpdate = getString(R.string.home_card_sensitive_update)
        override val sensitiveUnknown = getString(R.string.home_card_sensitive_unknown)
        override val sensitiveSummary: (Int) -> String = { getString(R.string.home_card_sensitive_summary, it) }
        override val lock = getString(R.string.home_card_lock)
        override val lockOff = getString(R.string.home_card_lock_off)
        override val lockRemaining: (String) -> String = { getString(R.string.home_card_lock_remaining, it) }
        override val ownDevice = getString(R.string.home_card_own_device)
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
        // 契约顺序：onCreate 先于挂载（TrackMap 排序契约，同 TrackRecordActivity）。
        map.onCreate(null)
        binding.mapContainer.addView(
            map.view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
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

/**
 * 面板两段式：功能卡（HomeData 驱动）在前，入口行在后。两段各自持有点击路由。
 */
class HomePanelAdapter(
    private val entries: List<HomeEntry>,
    private val onCardClick: (HomeCards.Kind) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var cards: List<HomeCards.Card> = emptyList()

    fun submitCards(cards: List<HomeCards.Card>) {
        this.cards = cards
        notifyDataSetChanged()
    }

    private class CardHolder(val view: View) : RecyclerView.ViewHolder(view)
    private class EntryHolder(val view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int): Int =
        if (position < cards.size) TYPE_CARD else TYPE_ENTRY

    override fun getItemCount(): Int = cards.size + entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_CARD) {
            CardHolder(inflater.inflate(R.layout.item_home_card, parent, false))
        } else {
            EntryHolder(inflater.inflate(R.layout.item_home_entry, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is CardHolder -> {
                val card = cards[position]
                holder.view.findViewById<TextView>(R.id.tv_home_card_title).text = card.title
                holder.view.findViewById<TextView>(R.id.tv_home_card_value).text = card.value
                holder.view.findViewById<TextView>(R.id.tv_home_card_line1).text = card.line1
                holder.view.findViewById<TextView>(R.id.tv_home_card_line2).text = card.line2
                holder.view.setOnClickListener { onCardClick(card.kind) }
            }
            is EntryHolder -> {
                val entry = entries[position - cards.size]
                holder.view.findViewById<ImageView>(R.id.iv_home_entry_icon).setImageResource(entry.iconRes)
                holder.view.findViewById<TextView>(R.id.tv_home_entry_title).setText(entry.titleRes)
                holder.view.setOnClickListener { entry.onClick() }
            }
        }
    }

    companion object {
        private const val TYPE_CARD = 0
        private const val TYPE_ENTRY = 1
    }
}
