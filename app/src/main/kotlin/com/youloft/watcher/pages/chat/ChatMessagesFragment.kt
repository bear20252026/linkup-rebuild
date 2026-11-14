package com.youloft.watcher.pages.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import java.io.File
import kotlinx.coroutines.launch

/**
 * The message surface: history on entry, the incoming stream while open, sends through the shared
 * [ChatViewModel]. Rows are text, image, voice (语音消息本轮接入：🎤 按住说话录一条，气泡按时长
 * 渲染、点击播放；播放走 MediaPlayer 直播 MinIO URL，`VoicePlayer` 是测试用的播放 seam) —
 * which is what the recovered EaseUI rows did before their SDK disappeared with the source tree.
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
    private lateinit var micPermission: androidx.activity.result.ActivityResultLauncher<String>

    private var recorder: VoiceRecorder? = null
    private var voicePlayer: VoicePlayer? = null
    private var recording = false

    /** 拍照/相册选图落 cache 再交给 transport（SDK 要真实文件路径）。 */
    private fun copyToCache(uri: android.net.Uri): String? = runCatching {
        val file = File(requireContext().cacheDir, "im-" + System.currentTimeMillis() + ".jpg")
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
        adapter = ChatMessageAdapter(onVoiceClick = ::toggleVoicePlayback)
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

        micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecording()
            } else {
                Toast.makeText(requireContext(), R.string.chat_voice_denied, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btChatVoice.setOnTouchListener { button, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        startRecording()
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (recording) finishRecording(discard = event.actionMasked == MotionEvent.ACTION_CANCEL)
                    button.isPressed = false
                    true
                }
                else -> false
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    if (state.rows != rows) {
                        rows.clear()
                        rows += state.rows
                        adapter.submit(rows, voicePlayer?.playingUrl)
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

    /** 权限已就绪后按下即录；按钮文字翻成"松开发送"。 */
    private fun startRecording() {
        val recorder = VoiceRecorder(File(requireContext().cacheDir, "voice"))
        if (!recorder.start()) {
            Toast.makeText(requireContext(), "录音启动失败", Toast.LENGTH_SHORT).show()
            return
        }

        this.recorder = recorder
        recording = true
        binding.btChatVoice.text = getString(R.string.chat_voice_recording)
    }

    /** 松开：录音交出即发送；划出取消；没录上（太短）提示。 */
    private fun finishRecording(discard: Boolean) {
        recording = false
        binding.btChatVoice.text = "🎤"
        val recorder = this.recorder ?: return
        this.recorder = null
        if (discard) {
            recorder.cancel()
            return
        }
        val result = recorder.stop()
        if (result == null) {
            Toast.makeText(requireContext(), R.string.chat_voice_too_short, Toast.LENGTH_SHORT).show()
            return
        }
        val (path, seconds) = result
        viewModel.sendVoice(path, seconds)
    }

    /** 点语音气泡：在播的停掉；否则换这条开始播。 */
    private fun toggleVoicePlayback(message: ImMessage) {
        val player = voicePlayer ?: MediaPlayerVoicePlayer {
            adapter.submit(rows, voicePlayer?.playingUrl)
        }.also { voicePlayer = it }
        message.mediaUrl?.let { player.start(it) }
        adapter.submit(rows, player.playingUrl)
    }

    override fun onDestroyView() {
        recorder?.cancel()
        recorder = null
        voicePlayer?.release()
        voicePlayer = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val EXTRA_PEER_ID = "peerUserId"
        const val EXTRA_PEER_NAME = "peerName"
    }
}

/** Two-row RecyclerView adapter: the message list has no selection state worth recycling into. */
class ChatMessageAdapter(private val onVoiceClick: (ImMessage) -> Unit) : RecyclerView.Adapter<ChatMessageAdapter.Holder>() {

    private val items = mutableListOf<ImMessage>()
    private var playingUrl: String? = null

    /** 新列表 + 播放中的 URL（null = 静默），两处状态都驱动重绑。 */
    fun submit(rows: List<ImMessage>, playingUrl: String? = null) {
        items.clear()
        items += rows
        this.playingUrl = playingUrl
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
        when {
            message.isVoice -> {
                holder.media.visibility = View.GONE
                holder.text.visibility = View.VISIBLE
                holder.text.text = formatVoice(message.durationSeconds ?: 0L, playingUrl == message.mediaUrl)
                holder.text.setOnClickListener { onVoiceClick(message) }
            }
            message.isImage -> {
                holder.text.visibility = View.GONE
                holder.media.visibility = View.VISIBLE
                holder.text.setOnClickListener(null)
                com.bumptech.glide.Glide.with(holder.media)
                    .load(message.mediaUrl)
                    .placeholder(R.drawable.ic_avatar_male)
                    .into(holder.media)
            }
            else -> {
                holder.media.visibility = View.GONE
                holder.text.visibility = View.VISIBLE
                holder.text.text = message.text
                holder.text.setOnClickListener(null)
            }
        }
    }

    class Holder(val text: android.widget.TextView, val media: android.widget.ImageView) :
        RecyclerView.ViewHolder(text)

    private companion object {
        const val TYPE_SENT = 0
        const val TYPE_RECEIVED = 1
    }
}

/** 气泡文案：播放中带 ▶ 提示；一分内秒数（5″），超一分分秒（1:05）。纯函数，JVM 钉住。 */
internal fun formatVoice(seconds: Long, playing: Boolean): String {
    val stamp = if (seconds >= 60) "%d:%02d".format(seconds / 60, seconds % 60) else "${seconds}″"
    return if (playing) "▶ $stamp" else "🎤 $stamp"
}
