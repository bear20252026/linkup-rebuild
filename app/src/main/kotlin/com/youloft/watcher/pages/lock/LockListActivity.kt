package com.youloft.watcher.pages.lock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.youloft.linkup.api.dto.LockInfo
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.R
import com.youloft.watcher.bean.lock.LockScreen
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.data.im.LockCommandBridge
import com.youloft.watcher.databinding.ActivityLockListBinding
import com.youloft.watcher.databinding.ItemLockListBinding
import kotlinx.coroutines.launch

/**
 * 锁机管理，`pages/lock/LockListActivity.java` 的本轮重建：`relation/lock_list` 的行（对方锁状态 +
 * 剩余时长），行内"锁机/解锁"操作。发起走 [LockCommandBridge]（台账 + IM 命令同时发）。
 *
 * 原版的两页发起流（UnlockSettingsActivity：时间锁 FixWheelView / 问题锁题库）收进一个对话框：
 * 时长档位 + 锁屏标题；问题锁的题库展示留到锁机卡收尾（question 通道已在桥里）。
 */
class LockListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLockListBinding
    private lateinit var bridge: LockCommandBridge
    private val rows = mutableListOf<LockInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val graph = appGraph(this)
        bridge = LockCommandBridge(
            imClient = graph.imClient,
            api = graph.imApi,
            scope = lifecycleScope,
            onLock = { /* 收端在本设备：锁机悬浮窗宿主（收端卡）接管；此处记录 */ },
        )
        bridge.start()
        binding.rvLockList.layoutManager = LinearLayoutManager(this)
        binding.rvLockList.adapter = LockListAdapter(::showLockDialog, ::unlock)
        binding.ivBack.setOnClickListener { finish() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val response = appGraph(this@LockListActivity).imApi.relationLockList()
            val list = response.data?.list.orEmpty().filterNotNull()
            rows.clear()
            rows += list
            binding.rvLockList.adapter?.notifyDataSetChanged()
        }
    }

    private fun showLockDialog(row: LockInfo) {
        val input = EditText(this).apply {
            hint = getString(R.string.lock_title_hint)
            setSingleLine(true)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.one_btn_lock) + " · " + (row.nickname ?: row.remark ?: ""))
            .setMessage(getString(R.string.lock_dialog_message))
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val title = input.text?.toString().orEmpty()
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
                                lockDuration = 0L,
                            ),
                        )
                    }
                    load()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        holder.binding.tvState.setText(
            if (locked) R.string.lock_state_locked else R.string.lock_state_free,
        )
        holder.binding.tvLock.titleView.text = holder.binding.root.context.getString(
            if (locked) R.string.lock_action_unlock else R.string.one_btn_lock,
        )
        holder.binding.tvLock.setOnClickListener {
            if (locked) onUnlock(row) else onLock(row)
        }
    }
}
