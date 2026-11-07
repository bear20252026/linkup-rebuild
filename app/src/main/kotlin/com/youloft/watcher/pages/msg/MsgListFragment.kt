package com.youloft.watcher.pages.msg

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.FragmentMsgListBinding
import com.youloft.watcher.pages.chat.ChatActivity
import kotlinx.coroutines.launch

/**
 * 消息中心，`pages/home/MsgListFragment.java` 的本轮重建：`rv_msg_list`（原版 SlideRecyclerView 的
 * 横滑行——已按反编译源移植该视图）列出 IM 会话，行点击进聊天页。会话来自 `ImClient.conversations()`，
 * 好友头像/昵称用 LinkUp 侧的 friendList 补齐（OpenIM 只有 userID）。
 */
class MsgListFragment : Fragment() {
    private var _binding: FragmentMsgListBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        FragmentMsgListBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = ConversationAdapter { conversation ->
            ChatActivity.start(requireContext(), conversation.peerUserId, conversation.peerName)
        }
        binding.rvMsgList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMsgList.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val graph = appGraph(requireContext())
                val friends = graph.friendList.friends.value.orEmpty()
                    .associateBy { it.id }
                val conversations = runCatching { graph.imClient.conversations() }
                    .getOrNull().orEmpty()
                    .map { row ->
                        val friend = friends[row.peerUserId]
                        ConversationRow(
                            peerUserId = row.peerUserId,
                            peerName = friend?.remark ?: friend?.nickname ?: row.peerUserId.toString(),
                            lastText = row.lastText,
                            unread = row.unread,
                            updatedAtSeconds = row.updatedAtSeconds,
                        )
                    }
                adapter.submit(conversations)
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
