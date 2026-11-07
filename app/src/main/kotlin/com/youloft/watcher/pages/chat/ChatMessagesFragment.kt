package com.youloft.watcher.pages.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.youloft.linkup.im.ImMessage
import com.youloft.watcher.R
import com.youloft.watcher.databinding.FragmentChatMessagesBinding
import com.youloft.watcher.databinding.ItemChatMessageReceivedBinding
import com.youloft.watcher.databinding.ItemChatMessageSentBinding
import kotlinx.coroutines.launch

/**
 * The message surface: history on entry, the incoming stream while open, sends through the shared
 * [ChatViewModel]. Two row types only — outgoing right-aligned, incoming left — which is what the
 * recovered EaseUI rows did before their SDK disappeared with the source tree.
 */
class ChatMessagesFragment : Fragment() {

    private var _binding: FragmentChatMessagesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChatViewModel by activityViewModels {
        val peerId = requireArguments().getLong(EXTRA_PEER_ID)
        ChatViewModel.factory(
            com.youloft.watcher.data.appGraph(requireContext()).imClient,
            peerId,
        )
    }
    private val rows = mutableListOf<ImMessage>()
    private lateinit var adapter: ChatMessageAdapter
    private lateinit var imagePicker: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest>

    /** 拍照/相册选图落 cache 再交给 transport（SDK 要真实文件路径）。 */
    private fun copyToCache(uri: android.net.Uri): String? = runCatching {
        val file = java.io.File(requireContext().cacheDir, "im-" + System.currentTimeMillis() + ".jpg")
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file.absolutePath
    }.getOrNull()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatMessagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ChatMessageAdapter()
        binding.rvChatMessages.adapter = adapter
        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }

        imagePicker = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            val path = uri?.let { copyToCache(it) }
            if (path != null) viewModel.sendImage(path)
        }
        binding.btChatImage.setOnClickListener {
            imagePicker.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                ),
            )
        }
        binding.btChatSend.setOnClickListener {
            val text = binding.etChatInput.text?.toString().orEmpty()
            binding.etChatInput.setText("")
            viewModel.send(text)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    if (state.rows != rows) {
                        rows.clear()
                        rows += state.rows
                        adapter.submit(rows)
                        if (rows.isNotEmpty()) {
                            binding.rvChatMessages.scrollToPosition(rows.size - 1)
                        }
                    }
                    binding.etChatInput.isEnabled = !state.sending
                }
            }
        }
        viewModel.start()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val EXTRA_PEER_ID = "peerUserId"
        const val EXTRA_PEER_NAME = "peerName"
    }
}

/** Two-row RecyclerView adapter: the message list has no selection state worth recycling into. */
class ChatMessageAdapter : RecyclerView.Adapter<ChatMessageAdapter.Holder>() {

    private val items = mutableListOf<ImMessage>()

    fun submit(rows: List<ImMessage>) {
        items.clear()
        items += rows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (items[position].incoming) TYPE_RECEIVED else TYPE_SENT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SENT) {
            val binding = ItemChatMessageSentBinding.inflate(inflater, parent, false)
            Holder(binding.tvChatText, binding.ivChatMedia)
        } else {
            val binding = ItemChatMessageReceivedBinding.inflate(inflater, parent, false)
            Holder(binding.tvChatText, binding.ivChatMedia)
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val message = items[position]
        if (message.isImage) {
            holder.text.visibility = android.view.View.GONE
            holder.media.visibility = android.view.View.VISIBLE
            com.bumptech.glide.Glide.with(holder.media)
                .load(message.mediaUrl)
                .placeholder(R.drawable.ic_avatar_male)
                .into(holder.media)
        } else {
            holder.media.visibility = android.view.View.GONE
            holder.text.visibility = android.view.View.VISIBLE
            holder.text.text = message.text
        }
    }

    class Holder(val text: android.widget.TextView, val media: android.widget.ImageView) :
        RecyclerView.ViewHolder(text)

    private companion object {
        const val TYPE_SENT = 0
        const val TYPE_RECEIVED = 1
    }
}
