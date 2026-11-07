package com.youloft.watcher.view.home.menu

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import com.bumptech.glide.Glide
import com.youloft.watcher.R
import com.youloft.watcher.databinding.ItemHomeMenuFriendsBinding
import com.youloft.watcher.databinding.ItemHomeMenuVipBinding
import com.youloft.linkup.api.dto.User

/**
 * The guard-tab top-right menu column, ported from
 * `_raw/out_jadx_156/sources/com/youloft/watcher/view/home/menu/MenuLayout.java`:
 * always contains [ItemFriends]; [ItemVip] joins when the account holds a VIP plan, sitting above the
 * friends card. `setOnlyItemFriendsVisible` drops the friends card's shadow and hides everything else —
 * the sheet drag uses it to fold the menu into the corner (the original's 0.8f threshold).
 *
 * The original's zoom-into-screen entry animation is a visual layer this rebuild simplifies: clicks
 * still route exactly where the original did (friend-switch dialog; VIP entry), and the deviation is
 * recorded in DESIGN.
 */
class MenuLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val itemFriends = ItemFriends(context)
    private var itemVip: ItemVip? = null
    var onlyItemFriendsVisible: Boolean = false
        private set

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(itemFriends)
    }

    /** `i(User)` — the VIP label card only ever joins for hadVipPlan accounts. */
    fun showVipWhenEligible(user: User) {
        if (itemVip == null && user.hadVipPlan) {
            val vip = ItemVip(context)
            addView(vip, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            itemVip = vip
        }
    }

    fun setOnFriendsClick(block: () -> Unit) {
        itemFriends.setOnClickListener { block() }
    }

    fun setOnVipClick(block: () -> Unit) {
        itemVip?.setOnClickListener { block() }
    }

    fun setOnlyItemFriendsVisible(only: Boolean) {
        if (only == onlyItemFriendsVisible) return
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === itemFriends) {
                child.elevation = if (only) 0f else 12f
            } else {
                child.visibility = if (only) GONE else VISIBLE
            }
        }
        onlyItemFriendsVisible = only
    }
}

/** The stacked-avatars card the friend-switch dialog opens from. */
class ItemFriends @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val binding = ItemHomeMenuFriendsBinding.inflate(LayoutInflater.from(context), this, true)

    fun setAvatars(urls: List<String>, extraCount: Int = 0) {
        val views = listOf(binding.ivFriendFirst, binding.ivFriendSecond, binding.ivFriendThird)
        views.forEachIndexed { index, view ->
            val url = urls.getOrNull(index)
            if (url == null) {
                view.visibility = GONE
            } else {
                view.visibility = VISIBLE
                Glide.with(this).load(url).placeholder(R.drawable.ic_avatar_male).into(view)
            }
        }
        binding.tvFriendCount.visibility = if (extraCount > 0) VISIBLE else GONE
        binding.tvFriendCount.text = "+$extraCount"
    }
}

/** The VIP corner label; the drawable flips with the channel build (`D0()==1`). */
class ItemVip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val binding = ItemHomeMenuVipBinding.inflate(LayoutInflater.from(context), this, true)

    fun setLabel(useSecond: Boolean) {
        binding.ivVipLabel.setImageResource(if (useSecond) R.drawable.ic_tab_map_vip_label2 else R.drawable.ic_tab_map_vip_label)
    }
}
