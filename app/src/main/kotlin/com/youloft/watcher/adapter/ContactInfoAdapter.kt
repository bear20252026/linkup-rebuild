package com.youloft.watcher.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.chad.library.adapter4.BaseQuickAdapter
import com.chad.library.adapter4.viewholder.QuickViewHolder
import com.youloft.linkup.api.dto.FriendInfo
import com.youloft.watcher.R
import com.youloft.watcher.databinding.ItemPhoneContactBinding
import com.youloft.watcher.ext.onIntervalClick

/**
 * Ported from `adapter/ContactInfoAdapter` in 1.5.6. Its rows are **people the server matched**, not
 * the raw address book — the list arrives on the search channel — so the row renders a match code and
 * an add affordance.
 *
 * The recovered class is built empty and handed `submitList(...)` plus a `notifyDataSetChanged()` on
 * every response; that double refresh is kept, because a row's click callback is installed on the
 * adapter instance the screen reuses.
 */
class ContactInfoAdapter(
    items: MutableList<FriendInfo> = mutableListOf(),
) : BaseQuickAdapter<FriendInfo, QuickViewHolder>(items) {

    var onAddFriend: (FriendInfo) -> Unit = {}

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int) =
        QuickViewHolder(R.layout.item_phone_contact, parent)

    override fun onBindViewHolder(holder: QuickViewHolder, position: Int, item: FriendInfo?) {
        // The original guarded here because the server list is a nullable-element list.
        if (item == null) return
        val binding = ItemPhoneContactBinding.bind(holder.itemView)
        val context = holder.itemView.context

        binding.tvName.text = item.displayName()
        // The recovered row concatenates the label and the id with no separator at all.
        binding.tvMatchCode.text = context.getString(R.string.add_friend_match_code) + item.userId
        binding.ivGender.setImageDrawable(
            when (item.sex) {
                "0" -> null
                "1" -> ContextCompat.getDrawable(context, R.drawable.ic_friend_male)
                else -> ContextCompat.getDrawable(context, R.drawable.ic_friend_female)
            },
        )
        item.avatar?.let { Glide.with(binding.ivAvatar).load(it).into(binding.ivAvatar) }

        binding.root.onIntervalClick { onAddFriend(item) }
        binding.lineBottom.visibility = if (position != itemCount - 1) View.VISIBLE else View.GONE
    }
}

/** `friendInfo.getName()` of the original: the remark the user wrote wins over the stranger's nickname. */
internal fun FriendInfo.displayName(): String = remark?.takeIf { it.isNotEmpty() } ?: nickname.orEmpty()
