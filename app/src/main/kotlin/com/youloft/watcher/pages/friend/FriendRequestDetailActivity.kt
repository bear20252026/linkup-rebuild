package com.youloft.watcher.pages.friend

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.youloft.linkup.api.dto.ConfirmAdd
import com.youloft.linkup.api.dto.FriendRequest
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.ActivityFriendRequestDetailBinding
import com.youloft.linkup.net.ApiResponse
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Rebuilt from the 1.5.6 screen of the same name: the requester's profile, one agree button, and the
 * three pre-conditions in front of it. There is deliberately no reject action — the original layout
 * has no such view, so inventing one would silently change the product.
 */
class FriendRequestDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFriendRequestDetailBinding

    private val viewModel: FriendRequestDetailViewModel by viewModels {
        FriendRequestDetailViewModel.factory(appGraph(this).friends)
    }

    private lateinit var request: FriendRequest

    private var myFriendLimit = false
    private var lastAgreeClickAt = 0L

    var navigation: FriendFlowNavigation = DefaultFriendFlowNavigation(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFriendRequestDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        request = intent.getSerializableExtra(EXTRA_DATA) as FriendRequest
        myFriendLimit = intent.getBooleanExtra(EXTRA_MY_FRIEND_LIMIT, false)

        binding.ivBack.setOnClickListener { finish() }
        binding.tvAgree.setOnClickListener { onAgreeClick() }

        render()
        viewModel.confirmAdd.observe(this) { onConfirmAdd(it) }
    }

    private fun render() {
        binding.tvName.text = request.nickname
        request.avatar?.let { Glide.with(binding.ivAvatar).load(it).into(binding.ivAvatar) }
        binding.tvBirthday.text = request.birthday?.date

        val sexText = when (request.sex) {
            SEX_MALE -> getString(R.string.man)
            SEX_FEMALE -> getString(R.string.woman)
            else -> null
        }
        val sexIcon = when (request.sex) {
            SEX_MALE -> R.drawable.ic_common_male_sign
            SEX_FEMALE -> R.drawable.ic_common_female_sign
            else -> null
        }
        binding.tvSex.text = sexText
        val drawable = sexIcon?.let { ContextCompat.getDrawable(this, it) }
        binding.tvSex.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, drawable, null)

        binding.tvMatchCode.text = Html.fromHtml(
            getString(R.string.mine_match_code, request.userId),
            Html.FROM_HTML_MODE_LEGACY,
        )

        renderRelation(request.relation)

        binding.tvAgree.isEnabled = request.status == STATUS_PENDING
        if (request.status != STATUS_PENDING) {
            binding.tvAgree.text = getString(R.string.friend_request_del_tips)
        }
    }

    private fun renderRelation(relation: Int) {
        when (relation) {
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
    }

    internal fun onAgreeClick() {
        val now = System.currentTimeMillis()
        if (now - lastAgreeClickAt < AGREE_DEBOUNCE_MILLIS) return
        lastAgreeClickAt = now

        val days = TimeUnit.MILLISECONDS.toDays(abs(now - request.createAt * 1000L))
        if (days >= EXPIRE_DAYS) {
            toast(getString(R.string.apply_expired))
            return
        }
        if (!appGraph(this).isVip && myFriendLimit) {
            navigation.showFriendNumLimitDialog()
            return
        }
        if (request.isFriendLimit) {
            toast(getString(R.string.ta_friend_num_limit_tips))
            return
        }
        viewModel.confirmAdd(request)
    }

    private fun onConfirmAdd(response: ApiResponse<ConfirmAdd>) {
        if (!response.isSuccess) {
            // The original toasts whenever `msg` is non-null here, even when it is empty — the list
            // screen checks the length instead. Both are reproduced as they are.
            response.msg?.let { toast(it) }
            return
        }
        if (appGraph(this).askPermissionsAfterAdd) {
            navigation.showPermissionSettings(request.userId)
        } else {
            navigation.openAgreeAdd()
            finish()
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        internal const val EXTRA_DATA = "data"
        internal const val EXTRA_MY_FRIEND_LIMIT = "myFriendLimit"
        private const val AGREE_DEBOUNCE_MILLIS = 500L
        private const val EXPIRE_DAYS = 30L
        private const val STATUS_PENDING = 0
        private const val SEX_MALE = 1
        private const val SEX_FEMALE = 2
        private const val RELATION_LOVER = 1
        private const val RELATION_FRIEND = 2
        private const val RELATION_FAMILY = 3

        fun start(context: Context, request: FriendRequest, myFriendLimit: Boolean) {
            context.startActivity(
                Intent(context, FriendRequestDetailActivity::class.java)
                    .putExtra(EXTRA_DATA, request)
                    .putExtra(EXTRA_MY_FRIEND_LIMIT, myFriendLimit),
            )
        }
    }
}
