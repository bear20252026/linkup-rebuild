package com.youloft.watcher.pages.friend

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.youloft.linkup.api.dto.FriendInfo
import com.youloft.linkup.api.dto.Permission
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.R
import com.youloft.watcher.adapter.FriendListVAdapter
import com.youloft.watcher.bean.CmdFriendBean
import com.youloft.watcher.data.FriendEvents
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.ActivityFriendInfoBinding
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Rebuilt from the 1.5.6 screen of the same name: profile, remark, relation, permission summary,
 * chat entry and delete. Loading or a missing record falls back to the "not friends" presentation,
 * which is exactly what the original did.
 */
class FriendInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFriendInfoBinding

    private val viewModel: FriendInfoViewModel by viewModels {
        FriendInfoViewModel.factory(appGraph(this).friends)
    }

    private var friendId = 0L
    private var nickNameFromIntent: String? = null
    private var relation = 0
    private var permission: Permission? = null

    var navigation: FriendFlowNavigation = DefaultFriendFlowNavigation(this)
    var dialogs: FriendInfoDialogs = RealFriendInfoDialogs(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFriendInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        friendId = intent.getLongExtra(EXTRA_ID, 0L)
        nickNameFromIntent = intent.getStringExtra(EXTRA_NICK_NAME)
        intent.getIntExtra(EXTRA_SOURCE, 0) // kept only for the analytics label below

        binding.ivBack.setOnClickListener { finish() }
        binding.ivDelete.setOnClickListener { onDeleteClick() }
        binding.rlRelation.setOnClickListener { onRelationClick() }
        binding.llPermissions.setOnClickListener { onPermissionsClick() }
        binding.ivChat.setOnClickListener { onChatClick() }
        binding.rlRemark.setOnClickListener { onRemarkClick() }

        viewModel.friendInfo.observe(this) { renderInfo(it) }
        viewModel.deleted.observe(this) { onDeleted(it) }
        viewModel.relation.observe(this) { onRelationUpdated(it) }
        viewModel.remark.observe(this) { onRemarkUpdated(it) }
        appGraph(this).friendList.friendCommand.observe(this) { onFriendCommand(it) }

        viewModel.loadFriendInfo(friendId)
    }

    private fun renderInfo(response: ApiResponse<FriendInfo>) {
        val info = response.data
        if (!response.isSuccess || info == null) {
            showNotFriends()
            return
        }
        // 原版用 Integer.parseInt(getSex()) / (getRelation())，字段缺失时会抛；这里取 0（即"未知"分支）。
        val sex = info.sex?.toIntOrNull() ?: 0
        Glide.with(binding.ivAvatar).load(info.avatar).into(binding.ivAvatar)
        binding.tvUsername.text = info.nickname
        if (!info.remark.isNullOrEmpty()) binding.tvRemark.text = info.remark

        binding.tvSex.text = when (sex) {
            SEX_MALE -> getString(R.string.man)
            SEX_FEMALE -> getString(R.string.woman)
            else -> null
        }
        val sexIcon = when (sex) {
            SEX_MALE -> R.drawable.ic_common_male_sign
            SEX_FEMALE -> R.drawable.ic_common_female_sign
            else -> null
        }
        binding.tvSex.setCompoundDrawablesRelativeWithIntrinsicBounds(
            null,
            null,
            sexIcon?.let { ContextCompat.getDrawable(this, it) },
            null,
        )

        binding.tvAddDate.text = SimpleDateFormat(
            ADD_DATE_PATTERN,
            FriendListVAdapter.locale(this),
        ).format(Date(info.createAt * 1000L))

        relation = info.relation?.toIntOrNull() ?: 0
        renderRelation(relation)
        binding.tvMatchCode.text = Html.fromHtml(
            getString(R.string.mine_match_code, info.userId),
            Html.FROM_HTML_MODE_LEGACY,
        )
        permission = info.permission
        renderPermissionSummary(info.permission)
    }

    private fun showNotFriends() {
        binding.tvUsername.text = nickNameFromIntent
        binding.tvMatchCode.text = Html.fromHtml(
            getString(R.string.mine_match_code, friendId),
            Html.FROM_HTML_MODE_LEGACY,
        )
        binding.rlRemark.visibility = View.GONE
        binding.rlRelation.visibility = View.GONE
        binding.llPermissions.visibility = View.GONE
        binding.llAddDate.visibility = View.GONE
        binding.ivDelete.visibility = View.GONE
        binding.toolbar.visibility = View.GONE
        binding.tvNotFriend.visibility = View.VISIBLE
    }

    private fun renderRelation(value: Int) {
        when (value) {
            RELATION_LOVER -> {
                binding.tvRelation.setText(R.string.lover)
                binding.ivRelation.setImageResource(R.drawable.ic_common_relation_lover)
            }
            RELATION_FRIEND -> {
                binding.tvRelation.setText(R.string.good_friend)
                binding.ivRelation.setImageResource(R.drawable.ic_common_relation_friend)
            }
            RELATION_FAMILY -> {
                binding.tvRelation.setText(R.string.relatives)
                binding.ivRelation.setImageResource(R.drawable.ic_common_relation_family)
            }
            else -> {
                binding.tvRelation.setText(R.string.other)
                binding.ivRelation.setImageResource(R.drawable.ic_common_relation_other)
            }
        }
        relation = value
    }

    /**
     * `w0()` in the original: how many of the nine sens switches the other side opened. Zero or a
     * missing permission object reads as "无可见数据" in the secondary colour.
     */
    private fun renderPermissionSummary(permission: Permission?) {
        var text = getString(R.string.no_data_is_visible)
        var color = ContextCompat.getColor(this, R.color.appSecondary)
        val granted = permission?.let {
            listOf(
                it.sensLocation,
                it.sensTrack,
                it.sensPhoneUse,
                it.sensCall,
                it.sensPhoneState,
                it.sensSensitive,
                it.sensAppUse,
                it.sensLockPhone,
                it.sensRealtimeVoice,
            ).count { value -> value }
        } ?: 0
        if (granted > 0) {
            text = getString(R.string.how_many_data, granted)
            color = ContextCompat.getColor(this, R.color.appPrimary)
        }
        binding.tvPermissions.text = text
        binding.tvPermissions.setTextColor(color)
    }

    private fun onDeleteClick() {
        dialogs.confirmDelete(getString(R.string.are_you_sure_to_delete_this_friend)) {
            viewModel.deleteFriend(friendId)
        }
    }

    private fun onDeleted(response: ApiResponse<Unit>) {
        if (!response.isSuccess) {
            val msg = response.msg
            if (!msg.isNullOrEmpty()) toast(msg)
            return
        }
        toast(getString(R.string.delete_success))
        FriendEvents.publishFriendDeleted(friendId)
        finish()
    }

    private fun onRelationClick() {
        if (relation <= 0) return
        dialogs.updateRelation(relation) { picked -> viewModel.updateRelation(friendId, picked) }
    }

    private fun onRelationUpdated(response: ApiResponse<Int>) {
        if (!response.isSuccess) {
            val msg = response.msg
            if (!msg.isNullOrEmpty()) toast(msg)
            return
        }
        renderRelation(response.data ?: 0)
        toast(getString(R.string.update_success))
    }

    private fun onPermissionsClick() {
        val current = permission ?: return
        // Opening the dialog is real; applying the choice is not wired. The recovered callback posts
        // the new settings and, on failure, refetches then re-syncs each permission type one by one
        // (`PrivacyRepository.updatePermissions`). That whole path is the privacy card's unit of work,
        // so no partial endpoint call is invented here.
        dialogs.permissionSettings(current) { }
    }

    private fun onChatClick() {
        navigation.openChat(friendId)
    }

    private fun onRemarkClick() {
        dialogs.updateRemark(
            getString(R.string.update_remark),
            binding.tvRemark.text.toString().trim(),
            getString(R.string.update_remark_hint),
        ) { remark -> viewModel.updateRemark(friendId, remark) }
    }

    private fun onRemarkUpdated(response: ApiResponse<String>) {
        if (!response.isSuccess) {
            val msg = response.msg
            if (!msg.isNullOrEmpty()) toast(msg)
            return
        }
        binding.tvRemark.text = response.data
        toast(getString(R.string.update_success))
    }

    private fun onFriendCommand(command: CmdFriendBean?) {
        if (command == null) return
        when (command.type) {
            CmdFriendBean.DELETE -> showDeleteOrLogout(NOT_FRIENDS_TOAST)
            CmdFriendBean.LOGOUT -> showDeleteOrLogout(UNREGISTERED_TOAST)
        }
    }

    private fun showDeleteOrLogout(text: String) {
        binding.viewDeleteOrLogout.visibility = View.VISIBLE
        binding.viewDeleteOrLogout.setOnClickListener { toast(text) }
    }

/** Recovered analytics label per entry source; the analytics sink itself is not rebuilt yet. */
    internal fun analyticsLabel(source: Int): String = when (source) {
        1 -> "守护Tab-头像功能弹窗"
        2 -> "消息中心-我的好友"
        3 -> "im头像"
        else -> ""
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        internal const val EXTRA_ID = "id"
        internal const val EXTRA_SOURCE = "source"
        internal const val EXTRA_NICK_NAME = "nickName"
        private const val ADD_DATE_PATTERN = "yyyy-MM-dd"
        private const val SEX_MALE = 1
        private const val SEX_FEMALE = 2
        private const val RELATION_LOVER = 1
        private const val RELATION_FRIEND = 2
        private const val RELATION_FAMILY = 3

        // Hardcoded in the original call site; the bank has no resource for either string.
        private const val NOT_FRIENDS_TOAST = "你们已不是好友"
        private const val UNREGISTERED_TOAST = "该账号已注销"

        fun start(context: Context, userId: Long, source: Int, nickName: String?) {
            context.startActivity(
                Intent(context, FriendInfoActivity::class.java)
                    .putExtra(EXTRA_ID, userId)
                    .putExtra(EXTRA_SOURCE, source)
                    .putExtra(EXTRA_NICK_NAME, nickName),
            )
        }
    }
}
