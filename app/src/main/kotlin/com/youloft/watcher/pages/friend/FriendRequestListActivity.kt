package com.youloft.watcher.pages.friend

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.youloft.linkup.api.dto.ConfirmAdd
import com.youloft.linkup.api.dto.FriendRequest
import com.youloft.linkup.api.dto.FriendRequestList
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.ActivityFriendRequestListBinding
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Rebuilt from the 1.5.6 screen of the same name. Paging, the quota flag and the click guards are
 * the Activity's own state in the original too, so they stay here rather than being moved into the
 * ViewModel.
 */
class FriendRequestListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFriendRequestListBinding

    private val viewModel: FriendRequestListViewModel by viewModels {
        FriendRequestListViewModel.factory(appGraph(this).friendRequests)
    }

    private val requests = mutableListOf<FriendRequest>()

    private val adapter by lazy {
        FriendRequestAdapter(requests).apply {
            setOnItemClickListener { _, _, position -> onRowClick(position) }
            addOnItemChildClickListener(R.id.tv_agree) { _, _, position -> onAgreeClick(position) }
        }
    }

    var navigation: FriendRequestNavigation = DefaultFriendRequestNavigation(this)

    private var page = 1
    private var loading = false
    private var exhausted = false
    private var friendLimit = false
    private var lastAgreeClickAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFriendRequestListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val layoutManager = LinearLayoutManager(this)
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, state: Int) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return
                if (layoutManager.childCount == 0) return
                if (layoutManager.findLastCompletelyVisibleItemPosition() >= layoutManager.itemCount - NEAR_BOTTOM) {
                    loadPage()
                }
            }
        })

        binding.ivBack.setOnClickListener { finish() }
        binding.ivAddFriend.setOnClickListener { navigation.openAddFriend() }

        viewModel.confirmAdd.asLiveData().observe(this) { onConfirmAdd(it) }

        loadPage()
    }

    internal fun loadPage() {
        if (loading || exhausted) return
        loading = true
        lifecycleScope.launch {
            val repository = appGraph(this@FriendRequestListActivity).friendRequests
            val response = try {
                repository.applyList(page = page, pageSize = PAGE_SIZE)
            } catch (error: Throwable) {
                error.printStackTrace()
                toast(getString(R.string.tips_net_err))
                ApiResponse<FriendRequestList>(status = ERROR_STATUS, msg = error.message)
            }
            loading = false
            if (!response.isSuccess) return@launch
            if (page == 1) friendLimit = response.data?.isFriendLimit == true
            page++
            exhausted = (response.data?.list?.size ?: 0) < PAGE_SIZE

            val batch = response.data?.list
            if (batch != null) {
                requests.addAll(batch.filterNotNull())
                adapter.notifyDataSetChanged()
            }
            binding.emptyView.layoutEmpty.visibility =
                if (requests.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun onConfirmAdd(response: ApiResponse<ConfirmAdd>) {
        if (!response.isSuccess) {
            val msg = response.msg
            if (!msg.isNullOrEmpty()) toast(msg)
            return
        }
        val data = response.data
        val friendUserId = data?.friendUserId ?: 0L
        if (appGraph(this).askPermissionsAfterAdd && friendUserId > 0) {
            navigation.showPermissionSettings(friendUserId)
        } else {
            navigation.openAgreeAdd()
            finish()
        }
        friendLimit = (appGraph(this).cachedFriendCount - 1) >= (data?.friendLimit ?: 0)

        val addedId = data?.friendUserId ?: return
        requests.forEachIndexed { index, request ->
            if (request.userId == addedId) {
                requests[index] = request.copy(status = STATUS_ADDED)
                adapter.notifyItemChanged(index)
            }
        }
    }

    internal fun onAgreeClick(position: Int) {
        val now = System.currentTimeMillis()
        if (now - lastAgreeClickAt < AGREE_DEBOUNCE_MILLIS) return
        lastAgreeClickAt = now

        val request = requests.getOrNull(position) ?: return
        val days = TimeUnit.MILLISECONDS.toDays(abs(now - request.createAt * 1000L))
        if (days >= EXPIRE_DAYS) {
            toast(getString(R.string.apply_expired))
            return
        }
        if (!appGraph(this).isVip && friendLimit) {
            navigation.showFriendNumLimitDialog()
            return
        }
        when {
            request.isUnregister -> toast(getString(R.string.user_done_unregister))
            !request.isFriendLimit -> viewModel.confirmAdd(request)
            else -> toast(getString(R.string.ta_friend_num_limit_tips))
        }
    }

    internal fun onRowClick(position: Int) {
        val request = requests.getOrNull(position) ?: return
        if (request.isUnregister) {
            toast(getString(R.string.user_done_unregister))
            return
        }
        when (request.status) {
            STATUS_PENDING, STATUS_ADDED_ALT -> navigation.openFriendRequestDetail(request, friendLimit)
            STATUS_ADDED -> navigation.openFriendInfo(request.userId)
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val PAGE_SIZE = 20
        private const val NEAR_BOTTOM = 3
        private const val AGREE_DEBOUNCE_MILLIS = 500L
        private const val EXPIRE_DAYS = 30L
        private const val STATUS_PENDING = 0
        private const val STATUS_ADDED = 1
        private const val STATUS_ADDED_ALT = 4
        private const val ERROR_STATUS = -1
    }
}
