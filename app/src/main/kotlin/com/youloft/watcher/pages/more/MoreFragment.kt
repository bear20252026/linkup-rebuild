package com.youloft.watcher.pages.more

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.FragmentMoreBinding
import com.youloft.watcher.pages.camera.CameraActivity
import com.youloft.watcher.pages.lock.LockListActivity
import com.youloft.watcher.pages.shot.ScreenshotActivity
import com.youloft.watcher.pages.voice.RealtimeVoiceActivity

/**
 * 更多 Tab，`pages/more/MoreFragment.java`：五个功能卡（锁机/实时语音/实时截屏/水印拍照/待办）。
 * 锁机/语音/截屏/相机由本轮各卡落地（LockListActivity / RealtimeVoiceActivity / ScreenshotActivity /
 * CameraActivity）；待办（TodoFragment）是独立卡未重建——入口保留、行为记录不跳。
 * 头像区显示好友（`lock_avatars_view` 等按原版语义喂好友头像）。
 */
class MoreFragment : Fragment() {
    private var _binding: FragmentMoreBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        FragmentMoreBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val graph = appGraph(requireContext())
        val avatars = graph.friendList.friends.value.orEmpty().mapNotNull { it.avatar ?: it.av }
        binding.lockAvatarsView.setAvatars(avatars)
        binding.voiceAvatarsView.setAvatars(avatars)
        binding.screenshotAvatarsView.setAvatars(avatars)

        binding.clLock.setOnClickListener { LockListActivity.start(requireContext()) }
        binding.clVoice.setOnClickListener { RealtimeVoiceActivity.start(requireContext()) }
        binding.clScreenshot.setOnClickListener { ScreenshotActivity.start(requireContext()) }
        binding.clCamera.setOnClickListener { CameraActivity.start(requireContext()) }
        binding.clTodo.setOnClickListener { }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
