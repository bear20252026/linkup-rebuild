package com.youloft.watcher.pages.friend

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.youloft.watcher.R
import com.youloft.watcher.adapter.FriendListVAdapter
import com.youloft.watcher.bean.CmdFriendBean
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.ActivityMyFriendBinding
import com.youloft.linkup.api.dto.FriendList
import com.youloft.watcher.view.decoration.FriendListVDecoration
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.whenStateAtLeast
import com.youloft.watcher.adapter.displayName

/**
 * Rebuilt from the 1.5.6 screen of the same name. This screen has no ViewModel and no paging: the
 * friend list is a live channel owned by the IM-backed manager, and the screen only orders it,
 * titles it, and reacts to pushes.
 */
class MyFriendActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyFriendBinding

    private val adapter = FriendListVAdapter()

    private var commandJob: Job? = null

    var navigation: FriendFlowNavigation = DefaultFriendFlowNavigation(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyFriendBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvFriendList.layoutManager = LinearLayoutManager(this)
        binding.rvFriendList.addItemDecoration(FriendListVDecoration())
        binding.rvFriendList.adapter = adapter

        adapter.isStateViewEnable = true
        adapter.setStateViewLayout(this, R.layout.view_friend_list_empty)
        adapter.stateView?.findViewById<View>(R.id.tv_empty_button)?.setOnClickListener {
            navigation.openAddFriend(ADD_FRIEND_MODE_EMPTY_VIEW)
        }
        adapter.setOnItemClickListener { _, _, position -> onRowClick(position) }

        binding.ivBack.setOnClickListener { finish() }
        binding.tvAddFriend.setOnClickListener { onAddFriendClick() }

        val source = appGraph(this).friendList
        source.friendCommand.observe(this) { onFriendCommand(it) }
        source.friends.observe(this) { render(it) }
    }

    internal fun render(list: List<FriendList.Info>) {
        // The recovered screen closes itself when there is nothing to show.
        if (list.isEmpty()) {
            finish()
            return
        }
        val mainUserId = appGraph(this).friendList.mainUserId()
        val mainUser = mutableListOf<FriendList.Info>()
        val others = mutableListOf<FriendList.Info>()
        list.filter { it.userId > 0 }.forEach { info ->
            if (info.userId == mainUserId) mainUser.add(0, info) else others.add(info)
        }
        mainUser.addAll(others)

        binding.tvTitle.text = getString(R.string.my_friend_num, mainUser.size)
        adapter.submitList(mainUser)
        binding.tvAddFriend.visibility = if (mainUser.isEmpty()) View.GONE else View.VISIBLE
    }

    internal fun onFriendCommand(command: CmdFriendBean?) {
        if (command == null || command.type == CmdFriendBean.ADD || command.type == CmdFriendBean.ADD_SELF) {
            commandJob?.cancel()
            commandJob = null
            return
        }
        // The recovered code consumed pushes with `launchWhenResumed`, which lifecycle 2.7 removed.
        // `whenStateAtLeast(RESUMED)` is its equivalent: wait for RESUMED, run once, suspend again if
        // the screen drops below it. Repeating per resume would re-fire the same push.
        commandJob = lifecycleScope.launch {
            lifecycle.whenStateAtLeast(Lifecycle.State.RESUMED) {
                appGraph(this@MyFriendActivity).friendList.run {
                    clearFriendCommand()
                    handleFriendCommand(this@MyFriendActivity, command)
                }
            }
        }
    }

    internal fun onAddFriendClick() {
        val now = System.currentTimeMillis()
        if (now - lastAddFriendClickAt < CLICK_INTERVAL_MILLIS) return
        lastAddFriendClickAt = now
        navigation.openAddFriend(ADD_FRIEND_MODE_MY_FRIEND_BUTTON)
    }

    private var lastAddFriendClickAt = 0L

    internal fun onRowClick(position: Int) {
        val info = adapter.getItem(position) ?: return
        if (info.userId == 0L) return
        if (info.unregisterTime > 0) {
            toast(getString(R.string.user_done_unregister))
        } else {
            navigation.openFriendInfo(info.userId, FRIEND_INFO_TYPE_MY_FRIEND, info.displayName())
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ADD_FRIEND_MODE_MY_FRIEND_BUTTON = 6
        const val ADD_FRIEND_MODE_EMPTY_VIEW = 0
        const val FRIEND_INFO_TYPE_MY_FRIEND = 2
        private const val CLICK_INTERVAL_MILLIS = 500L

        fun start(context: Context) {
            context.startActivity(Intent(context, MyFriendActivity::class.java))
        }
    }
}
