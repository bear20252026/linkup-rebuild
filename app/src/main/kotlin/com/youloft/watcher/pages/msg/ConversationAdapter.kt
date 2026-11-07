package com.youloft.watcher.pages.msg

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.youloft.watcher.databinding.ItemConversationBinding
import com.youloft.watcher.databinding.ItemConversationBinding as RowBinding

/** 消息中心的显示行：OpenIM 的会话 + LinkUp 侧补齐的昵称/头像（OpenIM 只带 userID）。 */
data class ConversationRow(
    val peerUserId: Long,
    val peerName: String,
    val lastText: String?,
    val unread: Int,
    val updatedAtSeconds: Long,
)

class ConversationAdapter(
    private val onOpen: (ConversationRow) -> Unit,
) : RecyclerView.Adapter<ConversationAdapter.Holder>() {

    private val rows = mutableListOf<ConversationRow>()

    fun submit(rows: List<ConversationRow>) {
        this.rows.clear()
        this.rows += rows
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.binding.tvConversationName.text = row.peerName
        holder.binding.tvConversationLast.text = row.lastText.orEmpty()
        holder.binding.tvConversationBadge.visibility =
            if (row.unread > 0) View.VISIBLE else View.GONE
        holder.binding.tvConversationBadge.text = if (row.unread > 99) "99+" else row.unread.toString()
        holder.itemView.setOnClickListener { onOpen(row) }
    }
}
