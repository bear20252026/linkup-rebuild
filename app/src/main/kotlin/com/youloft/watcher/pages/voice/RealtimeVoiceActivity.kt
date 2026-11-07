package com.youloft.watcher.pages.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.ActivityVoiceRealtimeBinding
import kotlinx.coroutines.launch
import com.youloft.watcher.pages.home.dialog.SwitchFriendDialog

/**
 * 实时语音，`pages/voice/RealtimeVoiceActivity` 的骨架版：入口、语音库（`api/index/realtime/voice/list`
 * 的 VoiceRsp 三个列表）与发送配额（remainTimes/sendLimit）已接，录音与上传的媒体链路在本轮
 * IM 媒体卡之后补（原版经火山 TOS，重建走 OpenIM 文件上传——待验 73）。
 */
class RealtimeVoiceActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVoiceRealtimeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceRealtimeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val response = runCatching {
                appGraph(this@RealtimeVoiceActivity).imApi.indexRealtimeVoiceList()
            }.getOrNull()
            val rsp = response?.data
            if (rsp == null || !response.isSuccess) {
                Toast.makeText(this@RealtimeVoiceActivity, R.string.home_data_server_error, Toast.LENGTH_SHORT).show()
                return@launch
            }
            binding.tvVoiceQuota.text = getString(
                R.string.voice_quota_format,
                rsp.remainTimes,
                rsp.sendLimit,
            )
        }
    }

    private fun pickFriendAndSend() {
        SwitchFriendDialog(this).show()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, RealtimeVoiceActivity::class.java))
        }
    }
}
