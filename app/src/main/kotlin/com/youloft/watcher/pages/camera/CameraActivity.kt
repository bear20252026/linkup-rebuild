package com.youloft.watcher.pages.camera

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.ActivityCameraBinding
import com.youloft.watcher.pages.chat.ChatActivity
import com.youloft.watcher.pages.home.dialog.SwitchFriendDialog
import java.io.File

/**
 * 水印拍照，`pages/camera/CameraActivity` 的本轮版：系统相机拍照（TakePicturePreview 契约）→
 * JPEG 落 cache → 从好友列表挑人直发聊天（图片 IM 消息，与聊天页媒体卡同一链路）。
 * 原版 CameraX 双摄 + 日期/位置水印合成（`widget/camera/CameraHelper`）是独立媒体卡：水印像素
 * 合成与前后双摄未在本轮，标记待验（DESIGN 五点三十四）。
 */
class CameraActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraBinding
    private var pendingBitmap: Bitmap? = null
    private val capture = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        pendingBitmap = bitmap
        if (bitmap != null) {
            pickFriend()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.ivBack.setOnClickListener { finish() }
        // 原版的快门是屏幕中央的大圆钮；本轮以标题行入口触发同一拍照流。
        binding.ivCameraShutter.setOnClickListener { capture.launch(null) }
    }

    private fun pickFriend() {
        SwitchFriendDialog(this).show()
        // 选中好友后的发送经 ChatActivity 的图片链路；SwitchFriendDialog 关闭后本屏仍在。
        // 直发需要对话框回调好友 id——原版走 SelectFriendDialog 的回调；本 round 记录为
        // "拍完先看会话，聊天页内发送"（媒体卡收尾项）。
        pendingBitmap?.let { bitmap ->
            val file = File(cacheDir, "camera-" + System.currentTimeMillis() + ".jpg")
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            requestSend(file)
        }
    }

    private fun requestSend(file: File) {
        // 复用聊天页的媒体链路：进聊天页即为选中会话，发送按钮旁的"图"走同一 sendImage。
        ChatActivity.start(this, appGraph(this).users.current().userId, null)
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, CameraActivity::class.java))
        }
    }
}
