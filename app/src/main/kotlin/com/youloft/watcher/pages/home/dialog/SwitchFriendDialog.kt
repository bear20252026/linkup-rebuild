package com.youloft.watcher.pages.home.dialog

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.data.local.GuardTarget
import com.youloft.watcher.databinding.DialogSwitchFriendBinding
import com.youloft.watcher.databinding.ItemSwitchFriendBinding
import com.youloft.watcher.databinding.ItemSwitchFriendAddBinding
import com.youloft.watcher.pages.friend.AddFriendActivity

/**
 * 好友切换弹窗，照搬 `pages/home/dialog/SwitchFriendDialog.java`：
 * 类型 0 = 好友行（按 createAt 倒序、当前守护对象置顶、userId>0），点击切换守护对象并 toast
 * "切换成功"；类型 1 = "添加好友"行 → 添加好友页（原版入口漏斗 tag "守护Tab-好友切换弹窗" 在
 * F0() 打点，本工程经 Recorder 语义记录在事件里）。
 */
class SwitchFriendDialog(context: Context) :
    android.app.Dialog(context, R.style.Theme_Watcher) {

    private val binding = DialogSwitchFriendBinding.inflate(LayoutInflater.from(context))
    private val guardTarget = GuardTarget(appGraph(context).keyValueStores)

    init {
        setContentView(binding.root)
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        val friends = appGraph(context).friendList.friends.value.orEmpty()
            .filter { it.id > 0 }
            .sortedByDescending { it.createAt }
            .toMutableList()
        val current = guardTarget.current()
        friends.firstOrNull { it.id == current }?.let { row ->
            friends.remove(row)
            friends.add(0, row)
        }
        binding.recyclerView.adapter = SwitchFriendAdapter(
            context,
            friends,
            current,
            onPicked = { picked ->
                guardTarget.switchTo(picked.id)
                Toast.makeText(context, R.string.switch_success, Toast.LENGTH_SHORT).show()
                dismiss()
            },
            onAdd = {
                dismiss()
                if (context is Activity) {
                    AddFriendActivity.start(context, source = 1)
                }
            },
        )
    }
}

private class SwitchFriendAdapter(
    private val context: Context,
    private val rows: List<com.youloft.linkup.api.dto.FriendList.Info>,
    private val current: Long,
    private val onPicked: (com.youloft.linkup.api.dto.FriendList.Info) -> Unit,
    private val onAdd: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemViewType(position: Int): Int =
        if (position == rows.size) TYPE_ADD else TYPE_FRIEND

    override fun getItemCount(): Int = rows.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_ADD) {
            AddHolder(ItemSwitchFriendAddBinding.inflate(LayoutInflater.from(context), parent, false))
        } else {
            FriendHolder(ItemSwitchFriendBinding.inflate(LayoutInflater.from(context), parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AddHolder -> holder.itemView.setOnClickListener { onAdd() }

            is FriendHolder -> {
                val row = rows[position]
                holder.binding.tvName.text = row.nickname ?: row.remark ?: row.id.toString()
                holder.binding.tvRelationShip.setText(relationLabel(row.relation))
                Glide.with(holder.itemView).load(row.avatar ?: row.av)
                    .placeholder(R.drawable.ic_avatar_male).into(holder.binding.ivAvatar)
                holder.binding.ivChecked.visibility =
                    if (row.id == current) View.VISIBLE else View.GONE
                holder.itemView.setOnClickListener { onPicked(row) }
            }
        }
    }

    /** `lover/good_friend/relatives/other` 的四档关系文案，与原版 SwitchFriendDialog 同序。 */
    private fun relationLabel(relation: Int): Int = when (relation) {
        1 -> R.string.lover
        2 -> R.string.good_friend
        3 -> R.string.relatives
        else -> R.string.other
    }

    companion object {
        const val TYPE_FRIEND = 0
        const val TYPE_ADD = 1
    }

    class FriendHolder(val binding: ItemSwitchFriendBinding) : RecyclerView.ViewHolder(binding.root)

    class AddHolder(binding: ItemSwitchFriendAddBinding) : RecyclerView.ViewHolder(binding.root)
}
