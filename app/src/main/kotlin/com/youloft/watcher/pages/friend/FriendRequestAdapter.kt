package com.youloft.watcher.pages.friend

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.chad.library.adapter4.BaseQuickAdapter
import com.chad.library.adapter4.viewholder.QuickViewHolder
import com.youloft.linkup.api.dto.FriendRequest
import com.youloft.watcher.R
import com.youloft.watcher.databinding.ItemFriendRequestBinding
import kotlin.math.abs

/**
 * Row binding ported from the recovered adapter (`pages/friend/t0.java`, BRVAH `adapter4`):
 * first row rounds its left corners, last row rounds its right ones, the divider goes invisible
 * on the last row, and the three action labels follow the recovered status machine.
 */
class FriendRequestAdapter(
    items: MutableList<FriendRequest>,
) : BaseQuickAdapter<FriendRequest, QuickViewHolder>(items) {

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int) =
        QuickViewHolder(R.layout.item_friend_request, parent)

    override fun onBindViewHolder(holder: QuickViewHolder, position: Int, item: FriendRequest?) {
        if (item == null) return
        val binding = ItemFriendRequestBinding.bind(holder.itemView)
        val state = rowState(item, System.currentTimeMillis(), position == 0, position == itemCount - 1)

        binding.tvName.text = item.nickname
        binding.ivRelation.setImageResource(relationIcon(item.relation))
        item.avatar?.let { Glide.with(binding.ivAvatar).load(it).into(binding.ivAvatar) }

        val radius = dp(binding.root.resources, CORNER_DP)
        binding.root.shapeAttrs
            .setTopLeftRadius(if (state.roundLeft) radius else 0f)
            .setBottomLeftRadius(if (state.roundLeft) radius else 0f)
            .render()
        binding.root.shapeAttrs
            .setTopRightRadius(if (state.roundRight) radius else 0f)
            .setBottomRightRadius(if (state.roundRight) radius else 0f)
            .render()

        binding.viewDivider.visibility = state.divider.toVisibility()
        binding.tvExpired.visibility = state.expired.toVisibility()
        binding.tvAdded.visibility = state.added.toVisibility()
        binding.tvAgree.visibility = state.agree.toVisibility()

        binding.llWarning.visibility = if (state.warning) View.VISIBLE else View.GONE
        // The original only overwrites the warning text when both flags hold, so a recycled row can
        // keep the previous row's text. Reproduced deliberately; fixing it is a product decision.
        if (state.warningTips) binding.tvWarningTips.setText(R.string.add_multiple_friends_recently)
    }

    private fun dp(resources: android.content.res.Resources, value: Int): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)

    private companion object {
        const val CORNER_DP = 28

        fun relationIcon(relation: Int) = when (relation) {
            1 -> R.drawable.ic_common_relation_lover
            2 -> R.drawable.ic_common_relation_friend
            3 -> R.drawable.ic_common_relation_family
            else -> R.drawable.ic_common_relation_other
        }
    }
}

enum class RowVisibility { VISIBLE, INVISIBLE, GONE }

internal fun RowVisibility.toVisibility(): Int = when (this) {
    RowVisibility.VISIBLE -> View.VISIBLE
    RowVisibility.INVISIBLE -> View.INVISIBLE
    RowVisibility.GONE -> View.GONE
}

/** `FriendRequest.isHighRisk()` / `.addMultipleFriends()` from the 1.5.6 bean. */
internal fun FriendRequest.isHighRisk(): Boolean = riskLevel == RISK_SUSPECTED

internal fun FriendRequest.addMultipleFriends(): Boolean = riskTypes?.contains(RISK_TYPE_MULTI_ADD) == true

internal data class FriendRequestRowState(
    val expired: RowVisibility,
    val added: RowVisibility,
    val agree: RowVisibility,
    val divider: RowVisibility,
    val warning: Boolean,
    val warningTips: Boolean,
    val roundLeft: Boolean,
    val roundRight: Boolean,
)

/**
 * The recovered state machine, kept free of view access so it is testable on its own. Note the
 * middle branch: the original shows `agree` for any status outside {1,4} as long as the request is
 * status 0 or younger than 30 days, so statuses other than 0 also get an actionable row.
 */
internal fun rowState(
    item: FriendRequest,
    nowMillis: Long,
    isFirst: Boolean,
    isLast: Boolean,
): FriendRequestRowState {
    val days = abs((nowMillis - item.createAt * 1000L) / MILLIS_PER_DAY)
    val actions = when {
        item.status == STATUS_ADDED || item.status == STATUS_ADDED_ALT ->
            Triple(RowVisibility.INVISIBLE, RowVisibility.VISIBLE, RowVisibility.GONE)

        item.status != STATUS_PENDING || days < EXPIRE_DAYS ->
            Triple(RowVisibility.INVISIBLE, RowVisibility.INVISIBLE, RowVisibility.VISIBLE)

        else ->
            Triple(RowVisibility.VISIBLE, RowVisibility.INVISIBLE, RowVisibility.INVISIBLE)
    }
    return FriendRequestRowState(
        expired = actions.first,
        added = actions.second,
        agree = actions.third,
        divider = if (isLast) RowVisibility.INVISIBLE else RowVisibility.VISIBLE,
        warning = item.isHighRisk(),
        warningTips = item.isHighRisk() && item.addMultipleFriends(),
        roundLeft = isFirst,
        roundRight = isLast,
    )
}

private const val STATUS_PENDING = 0
private const val STATUS_ADDED = 1
private const val STATUS_ADDED_ALT = 4
private const val EXPIRE_DAYS = 30L
private const val MILLIS_PER_DAY = 86_400_000L
private const val RISK_SUSPECTED = 3
private const val RISK_TYPE_MULTI_ADD = 4
