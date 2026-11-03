package com.youloft.watcher.adapter

import android.content.Context
import android.util.TypedValue
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.chad.library.adapter4.BaseQuickAdapter
import com.chad.library.adapter4.viewholder.QuickViewHolder
import com.youloft.linkup.api.dto.FriendList
import com.youloft.watcher.R
import com.youloft.watcher.databinding.ItemFriendListVBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ported from `adapter/FriendListVAdapter` in 1.5.6. The recovered class switches its whole row
 * layout on a construction-time `styleMode`, and every other friend screen reuses this adapter with
 * a different mode, so all four branches are kept even though this screen uses mode 0.
 */
class FriendListVAdapter(
    private val styleMode: Int = MODE_DEFAULT,
    items: MutableList<FriendList.Info> = mutableListOf(),
) : BaseQuickAdapter<FriendList.Info, QuickViewHolder>(items) {

    /** Only mode 3 (friend selection) reads this; it marks the row whose id matches. */
    var selectedUserId: Long = 0L

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int) =
        QuickViewHolder(R.layout.item_friend_list_v, parent)

    override fun onBindViewHolder(holder: QuickViewHolder, position: Int, item: FriendList.Info?) {
        if (item == null) return
        val binding = ItemFriendListVBinding.bind(holder.itemView)

        item.avatar?.let { Glide.with(binding.ivAvatar).load(it).into(binding.ivAvatar) }
        binding.tvName.text = item.displayName()
        binding.tvRelationDesc.setText(
            when (item.relation) {
                1 -> R.string.lover
                2 -> R.string.good_friend
                3 -> R.string.relatives
                else -> R.string.other
            },
        )

        when (styleMode) {
            MODE_DEFAULT -> {
                binding.ivSex.hide()
                binding.tvAddDate.hide()
                binding.cbSelect.hide()
            }
            MODE_COMPACT -> {
                binding.ivSex.hide()
                binding.tvAddDate.hide()
                binding.cbSelect.hide()
                binding.tvRelationDesc.hide()
                binding.ivAvatar.resizeTo(dp(48f), dp(48f))
            }
            MODE_SELECTABLE -> {
                binding.ivSex.hide()
                binding.tvAddDate.hide()
                binding.cbSelect.show()
                binding.cbSelect.isChecked = selectedUserId == item.userId
                binding.tvRelationDesc.hide()
                binding.ivAvatar.resizeTo(dp(48f), dp(48f))
            }            else -> {
                binding.ivSex.show()
                binding.tvAddDate.show()
                binding.cbSelect.show()
                binding.ivSex.setImageResource(
                    when (item.sex) {
                        1 -> R.drawable.ic_common_male_sign
                        2 -> R.drawable.ic_common_female_sign
                        else -> 0
                    },
                )
                binding.tvAddDate.text = context.getString(
                    R.string.add_time_format,
                    SimpleDateFormat(ADD_DATE_PATTERN, locale(context)).format(Date(item.createAt * 1000L)),
                )
                binding.cbSelect.isChecked = item.permissionValue
            }
        }
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics,
        ).toInt()

    companion object {
        const val MODE_DEFAULT = 0
        const val MODE_COMPACT = 2
        const val MODE_SELECTABLE = 3

        /** `ag.a.f1388q`, the pattern the recovered date util is called with here. */
        const val ADD_DATE_PATTERN = "yyyy-MM-dd"

        /** The recovered formatter takes the device's first configured locale, not a fixed one. */
        fun locale(context: Context): Locale = context.resources.configuration.locales[0]
    }
}

/** `remark` wins over `nickname`, matching the recovered `Info.getName()`. */
internal fun FriendList.Info.displayName(): String? =
    if (remark.isNullOrEmpty()) nickname else remark

private fun android.view.View.hide() {
    visibility = android.view.View.GONE
}

private fun android.view.View.show() {
    visibility = android.view.View.VISIBLE
}

private fun android.view.View.resizeTo(width: Int, height: Int) {
    layoutParams = layoutParams.apply {
        this.width = width
        this.height = height
    }
}
