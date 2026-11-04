package com.youloft.watcher.pages.friend.dialog

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.mc.fastkit.dialog.BindingDialog
import com.youloft.linkup.api.dto.Friend
import com.youloft.watcher.R
import com.youloft.watcher.databinding.DialogVerifyInfoBinding
import com.youloft.watcher.ext.onIntervalClick

/**
 * Recovered from `pages.friend.dialog.VerifyInfoDialog` — the bottom sheet that shows the stranger the
 * user just looked up, asks which relation to record, and hands the choice back.
 *
 * `onConfirm(relation, dismissParent)` mirrors the original's two-argument callback: the second lambda
 * is what the *caller* uses to close this sheet once its own follow-up finished.
 */
class VerifyInfoDialog(
    context: Context,
    private val friend: Friend,
    private val source: Int,
    private val mySex: Int,
    private val onVipLabelClick: (source: Int, label: String) -> Unit,
    private val onConfirm: (Int, () -> Unit) -> Unit,
) : BindingDialog<DialogVerifyInfoBinding>(context) {

    private var relation = RELATION_OTHER

    /** Which entry opened the sheet, in the words the original reported. Its analytics sink is pending. */
    val enterLabel: String get() = sourceLabel(source)

    override fun onInit(binding: DialogVerifyInfoBinding) {
        binding.tvName.text = friend.nickname
        binding.tvMatchCode.text = context.getString(R.string.match_code_num, friend.userId)
        applyOtherSex(friend.sex?.toIntOrNull() ?: 0)
        friend.avatar?.let { Glide.with(binding.ivAvatar).load(it).into(binding.ivAvatar) }

        // A stranger who is already at their quota cannot be added: the button is replaced by the
        // membership label, and that label is itself the upsell entry.
        binding.ivLabelVip.visibility = if (friend.isFriendLimit) View.VISIBLE else View.GONE
        binding.tvConfirm.visibility = if (friend.isFriendLimit) View.GONE else View.VISIBLE

        binding.relationButton.onRelationChanged = { relation = it }
        binding.ivClose.onIntervalClick { dismissDelayed() }
        binding.ivLabelVip.onIntervalClick { onVipLabelClick(VIP_SOURCE_MULTIPLE_FRIENDS, VIP_LABEL) }
        binding.tvConfirm.onIntervalClick {
            if (friend.isFriendLimit) return@onIntervalClick
            onConfirm(relation) { dismissDelayed() }
            dismissDelayed()
        }
    }

    /**
     * The original painted the other person's sex onto the name and pre-selected a relation from the
     * pair: opposite sexes 恋人, two men 挚友, an unknown sex 其他.
     */
    private fun applyOtherSex(otherSex: Int) {
        val icon: Drawable?
        val picked: Int
        when (otherSex) {
            SEX_MALE -> {
                icon = ContextCompat.getDrawable(context, R.drawable.ic_common_male_sign)
                picked = when (mySex) {
                    SEX_MALE -> RELATION_FRIEND
                    SEX_FEMALE -> RELATION_LOVER
                    else -> RELATION_OTHER
                }
            }

            SEX_FEMALE -> {
                icon = ContextCompat.getDrawable(context, R.drawable.ic_common_female_sign)
                picked = when (mySex) {
                    SEX_MALE -> RELATION_LOVER
                    SEX_FEMALE -> RELATION_FRIEND
                    else -> RELATION_OTHER
                }
            }

            else -> {
                icon = null
                picked = RELATION_OTHER
            }
        }
        icon?.setBounds(0, 0, icon.minimumWidth, icon.minimumHeight)
        binding.tvName.setCompoundDrawables(null, null, icon, null)
        relation = picked
        binding.relationButton.setRelation(relation)
    }

    internal fun pickedRelation(): Int = relation

    companion object {
        const val SEX_MALE = 1
        const val SEX_FEMALE = 2
        const val RELATION_LOVER = 1
        const val RELATION_FRIEND = 2
        const val RELATION_FAMILY = 3
        const val RELATION_OTHER = 4
        const val VIP_SOURCE_MULTIPLE_FRIENDS = 16
        const val VIP_LABEL = "多个好友"

        /** The `type` each caller passed, and the label the dialog reported for it (sink is pending). */
        internal fun sourceLabel(type: Int): String = when (type) {
            1 -> "扫一扫"
            2 -> "搜索匹配码"
            3 -> "搜索手机号"
            4 -> "手机通讯录"
            5 -> "H5"
            else -> ""
        }
    }
}
