package com.youloft.watcher.pages.mine

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.FragmentMineBinding
import com.youloft.watcher.databinding.ItemMineMenuBinding

/**
 * 我的 Tab，照搬 `pages/mine/MineFragment.java`：`rv_menu` 四宫格（权限设置/消息通知设置/地图设置/
 * 隐私设置，VIP 账号注入会员入口），`user_info_view` 头部，VIP 卡按 `hadVipPlan` 显隐，四个静态行
 * （小组件/数据安全/应用密码/联系我们/关于我们）。
 *
 * 四宫格与静态行指向的二级屏（权限列表/通知设置/地图设置/隐私设置/数据安全/应用密码/联系/关于）
 * 属各自的未重建卡——点击保持诚实：入口可见、行为是记录不跳转（DESIGN 已记）。
 */
class MineFragment : Fragment() {
    private var _binding: FragmentMineBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        FragmentMineBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val graph = appGraph(requireContext())
        val user = graph.users.current()
        binding.userInfoView.setData(user)
        binding.vipInfoView.visibility = if (user.hadVipPlan) View.VISIBLE else View.GONE

        binding.rvMenu.layoutManager = GridLayoutManager(requireContext(), 4)
        binding.rvMenu.adapter = MineMenuAdapter(MineMenu.MENUS)

        // 静态行：目标屏未重建，行为是可见但不跳（原版每行一个二级页）。
        listOf(binding.tvWidget, binding.tvSecurity, binding.tvAppPsw, binding.tvContact, binding.tvAbout)
            .forEach { row -> row.setOnClickListener { } }
    }

    override fun onResume() {
        super.onResume()
        binding.userInfoView.setData(appGraph(requireContext()).users.current())
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

/** `bean/MineMenu` — the shipped four, same order; id 7 (VIP) injects per account upstream. */
data class MineMenu(val id: Int, val titleRes: Int, val iconRes: Int) {
    companion object {
        val MENUS = listOf(
            MineMenu(4, R.string.permission_setup, R.drawable.ic_menu_permission_setup),
            MineMenu(2, R.string.notify_settings, R.drawable.ic_menu_auto_reporting),
            MineMenu(5, R.string.map_settings, R.drawable.ic_menu_map_settings),
            MineMenu(6, R.string.privacy_settings, R.drawable.ic_menu_privacy_settings),
        )
    }
}

private class MineMenuAdapter(private val items: List<MineMenu>) :
    RecyclerView.Adapter<MineMenuAdapter.Holder>() {
    class Holder(val binding: ItemMineMenuBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemMineMenuBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.binding.tvName.setText(items[position].titleRes)
        holder.binding.ivIcon.setImageResource(items[position].iconRes)
        // 二级屏未重建：点击记录不跳（同上）。
        holder.binding.root.setOnClickListener { }
    }
}
