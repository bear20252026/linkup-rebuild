package com.youloft.watcher.view.mine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.mc.fastkit.view.shape.ShapedTextView
import com.youloft.linkup.api.dto.User
import com.youloft.watcher.R
import com.youloft.watcher.data.local.isVip
import com.youloft.watcher.databinding.ViewAvatarsBinding
import com.youloft.watcher.databinding.ViewUserInfoBinding

/**
 * The mine-tab header views, bodies ported from `_raw/out_jadx_156/sources/com/youloft/watcher/view/mine/`
 * (UserInfoView.java / AvatarsView.java). The original image loader (`fastkit.widget.i`) was recovered as
 * Glide direct (recovered 待验 11): placeholder/corner/fade fidelity stays unverified on a device.
 */
class AvatarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    private val binding = ViewAvatarsBinding.inflate(LayoutInflater.from(context), this, true)

    /** Margin adjustment the more-tab caller used; ids per view_avatars.xml. */
    fun inset(startSecond: Int? = null, startThird: Int? = null) {
        startSecond?.let {
            (binding.ivAvatarSecond.layoutParams as ConstraintLayout.LayoutParams).marginStart = it
            binding.ivAvatarSecond.layoutParams = binding.ivAvatarSecond.layoutParams
        }
        startThird?.let {
            (binding.ivAvatarThird.layoutParams as ConstraintLayout.LayoutParams).marginStart = it
            binding.ivAvatarThird.layoutParams = binding.ivAvatarThird.layoutParams
        }
    }

    fun style(sizeSp: Float, textColor: Int, typeface: Typeface?, contentColor: Int) {
        binding.tvNumber.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        binding.tvNumber.setTextColor(textColor)
        typeface?.let { binding.tvNumber.typeface = it }
    }

    fun setAvatarSize(size: Int) {
        listOf(binding.ivAvatarFirst, binding.ivAvatarSecond, binding.ivAvatarThird).forEach {
            it.layoutParams = it.layoutParams.apply { width = size; height = size }
        }
    }

    /** One row per head-count branch of the original. */
    fun setAvatars(avatars: List<String>) {
        when (avatars.size) {
            0 -> hideAll()
            1 -> {
                show(1)
                load(binding.ivAvatarFirst, avatars[0])
            }

            2 -> {
                show(2)
                load(binding.ivAvatarFirst, avatars[0]); load(binding.ivAvatarSecond, avatars[1])
            }

            3 -> {
                show(3, number = false)
                load(binding.ivAvatarFirst, avatars[0]); load(binding.ivAvatarSecond, avatars[1])
                load(binding.ivAvatarThird, avatars[2])
            }

            else -> {
                show(3)
                load(binding.ivAvatarFirst, avatars[0]); load(binding.ivAvatarSecond, avatars[1])
                load(binding.ivAvatarThird, avatars[2])
                binding.tvNumber.text = (avatars.size - 2).toString()
            }
        }
    }

    private fun show(count: Int, number: Boolean = true) {
        binding.ivAvatarFirst.visibility = VISIBLE
        binding.ivAvatarSecond.visibility = if (count >= 2) VISIBLE else GONE
        binding.ivAvatarThird.visibility = if (count >= 3) VISIBLE else GONE
        binding.tvNumber.visibility = if (number) VISIBLE else GONE
    }

    private fun hideAll() {
        binding.ivAvatarFirst.visibility = GONE
        binding.ivAvatarSecond.visibility = GONE
        binding.ivAvatarThird.visibility = GONE
        binding.tvNumber.visibility = GONE
    }

    private fun load(view: ShapeableImageView, url: String?) {
        Glide.with(view).load(url).placeholder(R.drawable.ic_avatar_male).into(view)
    }
}

/**
 * The single/double avatar header with the match-code copy row; branch order is the original's
 * (anniversary-with-other-user first, plain account second).
 */
class UserInfoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    private val binding = ViewUserInfoBinding.inflate(LayoutInflater.from(context), this, true)

    fun setData(user: User) {
        val anniversary = user.anniversary
        if (anniversary != null && anniversary.userId != 0L) {
            binding.flAvatarBg.visibility = VISIBLE
            binding.flMineAvatarBg.visibility = GONE
            binding.flFriendAvatarBg.visibility = GONE
            Glide.with(this).load(user.avatar).placeholder(R.drawable.ic_avatar_male).into(binding.ivMineAvatar)
            Glide.with(this).load(anniversary.avatar).placeholder(R.drawable.ic_avatar_male).into(binding.ivFriendAvatar)
            binding.ivMineVipLabel.visibility = if (user.isVip()) VISIBLE else GONE
            binding.llNickname.visibility = VISIBLE
            binding.llTogetherDay.visibility = GONE
            binding.tvTogetherNum.text = ((anniversary.anniversaryTime ?: 0L) / 86_400L).toString()
            binding.tvTogether.text = anniversary.title?.takeIf { it.isNotEmpty() }
                ?: context.getString(R.string.set_your_anniversary)
            return
        }
        binding.flAvatarBg.visibility = GONE
        binding.flMineAvatarBg.visibility = VISIBLE
        binding.flFriendAvatarBg.visibility = VISIBLE
        Glide.with(this).load(user.avatar).placeholder(R.drawable.ic_avatar_male).into(binding.ivAvatar)
        binding.ivVipLabel.visibility = if (user.isVip()) VISIBLE else GONE
        binding.llTogetherDay.visibility = GONE
        binding.llNickname.visibility = VISIBLE
        binding.tvUsername.text = user.nickname
        // Four-digit grouping, copy carries the bare id (recovered rule from the add-friend round).
        val grouped = user.userId.toString().chunked(4).joinToString(" ")
        binding.tvMatchCode.text = context.getString(R.string.mine_match_code_text) + grouped
        binding.tvMatchCode.setOnClickListener {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("matchCode", user.userId.toString()))
            Toast.makeText(context, R.string.copy_success, Toast.LENGTH_SHORT).show()
        }
    }
}
