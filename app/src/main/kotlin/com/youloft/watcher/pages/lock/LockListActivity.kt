package com.youloft.watcher.pages.lock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.youloft.linkup.api.dto.LockInfo
import com.youloft.watcher.R
import com.youloft.watcher.bean.lock.LockScreen
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.data.im.LockCommandBridge
import com.youloft.watcher.databinding.ActivityLockListBinding
import com.youloft.watcher.databinding.ItemLockListBinding
import com.youloft.watcher.utils.LockOverlayWindow
import kotlinx.coroutines.launch

/**
 * 锁机管理，`pages/lock/LockListActivity.java` 的本轮重建：`relation/lock_list` 的行（对方锁状态 +
 * 剩余时长 + 版本行 av/client/osVersion——待验 77），行内"锁机/解锁"操作。发起走
 * [LockCommandBridge]（台账 + IM 命令同时发）；时间锁发起流是 [UnLockTimeSheet]（滚轮 + 档位）。
 *
 * 原版 permTips 横幅接活：本机没开 SYSTEM_ALERT_WINDOW 时显示"我的锁机权限未开启"，去设置跳
 * 系统授权页（原版同款文案与入口）。
 */
class LockListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLockListBinding
    private lateinit var bridge: LockCommandBridge
    private val adapter = LockListAdapter(onLock = { row -> UnLockTimeSheet(this, row) { screen, title -> sendLock(row, screen, title) } }, onUnlock = ::unlock)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val graph = appGraph(this)
        bridge = LockCommandBridge(
            imClient = graph.imClient,
            api = graph.imApi,
            scope = lifecycleScope,
            onLock = { /* 收端在本设备：锁机盖板/降级 Activity 由 MainActivity 的命令桥接管 */ },
        )
        bridge.start()
        binding.rvLockList.layoutManager = LinearLayoutManager(this)
        binding.rvLockList.adapter = adapter
        binding.ivBack.setOnClickListener { finish() }
        wirePermTips()
        load()
    }

    /** 原版 permTips：无 overlay 权限 → 横幅可见，"去设置"进系统授权页。 */
    private fun wirePermTips() {
        val hasPermission = LockOverlayWindow.canShow(this)
        binding.permTipsLayout.visibility = if (hasPermission) View.GONE else View.VISIBLE
        binding.tvSetup.setOnClickListener { LockOverlayWindow.openSettings(this) }
        binding.ivClosePermTips.setOnClickListener { binding.permTipsLayout.visibility = View.GONE }
    }

    override fun onResume() {
        super.onResume()
        // 授权页返回后横幅要重估（原版同刻刷新）。
        wirePermTips()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val response = runCatching { appGraph(this@LockListActivity).imApi.relationLockList() }.getOrNull()
            val list = if (response?.isSuccess == true) {
                response.data?.list.orEmpty().filterNotNull()
            } else {
                emptyList()
            }
            adapter.submit(list)
        }
    }

    private fun sendLock(row: LockInfo, screen: LockScreen, title: String) {
        lifecycleScope.launch {
            runCatching {
                bridge.sendLock(
                    row.userId,
                    LockScreen(
                        userid = row.userId,
                        nickName = row.nickname,
                        avatar = row.avatar,
                        lockType = LockCommandBridge.LOCK_TYPE_REMOTE_LOCK,
                        lockTitle = title.ifBlank { null },
                        lockDuration = screen.lockDuration,
                    ),
                )
            }
            load()
        }
    }

    private fun unlock(row: LockInfo) {
        lifecycleScope.launch {
            runCatching { bridge.sendUnlock(row.userId) }
            load()
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, LockListActivity::class.java))
        }
    }
}

private class LockListAdapter(
    private val onLock: (LockInfo) -> Unit,
    private val onUnlock: (LockInfo) -> Unit,
) : RecyclerView.Adapter<LockListAdapter.Holder>() {

    private val rows = mutableListOf<LockInfo>()

    fun submit(rows: List<LockInfo>) {
        this.rows.clear()
        this.rows += rows
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemLockListBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemLockListBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val locked = row.lockTime > 0
        holder.binding.tvName.text = row.nickname ?: row.remark ?: row.userId.toString()
        // 版本行（待验 77）：对方设备上报什么显示什么，未上报就整行收起。
        val version = listOfNotNull(
            row.client.takeIf { it.isNotBlank() },
            row.av.takeIf { it.isNotBlank() },
            row.osVersion?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        holder.binding.tvVersion.visibility = if (version.isBlank()) View.GONE else View.VISIBLE
        holder.binding.tvVersion.text = version
        holder.binding.tvState.setText(
            if (locked) R.string.lock_state_locked else R.string.lock_state_free,
        )
        holder.binding.tvLock.setOnClickListener {
            if (locked) onUnlock(row) else onLock(row)
        }
    }
}
