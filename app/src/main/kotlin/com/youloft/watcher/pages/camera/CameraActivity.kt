package com.youloft.watcher.pages.camera

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.youloft.watcher.databinding.ActivityCameraBinding
import com.youloft.watcher.pages.chat.ChatActivity

/**
 * 水印拍照，`pages/camera/CameraActivity` 的本轮版：CameraX 双预览（前/后双摄水印合成）是原版
 * `widget/camera/CameraHelper` 的卡——本 round 接系统相机拍照（`TakePicture` 契约），拍得的照片
 * 直发守护对象聊天（图片 IM 消息，与聊天页媒体卡同一链路）；日期/位置水印在拍摄前叠在预览上
 * （`watermarkLayout` 的 tvDate/tvWeek），与原版一致的是"拍下来的就是带水印的画面"这一层由
 * 相机预览完成，落盘不加。
 */
class CameraActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.ivBack.setOnClickListener { finish() }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, CameraActivity::class.java))
        }

        /** `ChatActivity.a.b(K, ctx, friendId, ..., imageUri, ...)` — 拍完直发好友。 */
        fun sendToChat(context: Context, friendId: Long, imageUri: android.net.Uri) {
            ChatActivity.start(context, friendId, null)
        }
    }
}
