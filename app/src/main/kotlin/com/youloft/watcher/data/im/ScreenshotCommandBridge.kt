package com.youloft.watcher.data.im

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.youloft.linkup.im.CmdType
import com.youloft.linkup.im.ImClient
import com.youloft.linkup.net.LinkUpApi
import com.youloft.watcher.bean.CmdScreenShot
import com.youloft.watcher.service.AppAccessibilityService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 截屏命令桥（原版 `widget/im/f` 的 screenShot 分支 + `pages/shot/j0` 状态机的两端）：
 *
 * - **采集端**（收命令）：请求到达 → 回 step1（无障碍是否就绪）→ takeScreenshot → 回 step2
 *   （成败）→ PNG 落 cache → 上传 → 回 step3（图 URL）。上传与聊天图片同一条 OpenIM/MinIO 链，
 *   与原版的偏离记录在 DESIGN：原版 TOS 上传不落会话，本重建的图会以图片消息出现在双方会话里。
 * - **发起端**（[request]）：发 `step=1, callBack=0`；回执从 [replies] 流出，由弹窗按步推进。
 *
 * 采集端只信任确认过的好友（审计 P0-3）：[trusted] 不过的请求直接丢弃——静默截屏是
 * 整屏内容出设备，不能"谁发都采"。
 */
class ScreenshotCommandBridge(
    private val context: Context,
    private val imClient: ImClient,
    private val api: LinkUpApi,
    private val scope: CoroutineScope,
    private val trusted: suspend (peerUserId: Long) -> Boolean,
    private val myUserId: () -> Long,
) {
    /** 发起端收到的回执流（callBack=1 的命令）；弹窗订阅它推进 step 1→3。 */
    val replies = kotlinx.coroutines.flow.MutableSharedFlow<CmdScreenShot>(extraBufferCapacity = 16)

    fun start() {
        imClient.commands
            .onEach { command ->
                if (command.action != CmdType.SCREEN_SHOT) return@onEach
                val payload = CmdScreenShot.fromJson(command.data) ?: return@onEach
                if (payload.callBack == CALLBACK_REPLY) {
                    // 发起端只消费回执；"对方给我发的请求"不进这条流。
                    if (payload.userid != myUserId()) replies.tryEmit(payload)
                    return@onEach
                }
                if (!trusted(command.peerUserId)) {
                    Log.w(TAG, "screenshot request from non-friend ${command.peerUserId} dropped")
                    return@onEach
                }
                handleRequest(command.peerUserId)
            }
            .launchIn(scope)
    }

    /** 采集端三步回执。原版对"命令里 userid 是请求方 id"的语义照搬：回执 userid 换成自己。 */
    private fun handleRequest(requesterId: Long) {
        val available = AppAccessibilityService.canTakeScreenshot()
        reply(requesterId, step = 1, available = available)
        if (!available) return
        AppAccessibilityService.takeScreenshot { bitmap ->
            if (bitmap == null) {
                reply(requesterId, step = 2, success = false)
                return@takeScreenshot
            }
            reply(requesterId, step = 2, success = true)
            scope.launch {
                val file = writePng(bitmap)
                if (file == null) {
                    reply(requesterId, step = 3, imageUrl = null)
                    return@launch
                }
                // 上传链与聊天图片同一条（sendImage 捎带 MinIO 上传，返回落位 URL）。
                val url = runCatching { imClient.sendImage(requesterId, file.absolutePath) }.getOrNull()
                reply(requesterId, step = 3, imageUrl = url)
            }
        }
    }

    private fun reply(requesterId: Long, step: Int, available: Boolean = true, success: Boolean = false, imageUrl: String? = null) {
        scope.launch {
            val payload = CmdScreenShot(
                userid = myUserId(),
                step = step,
                callBack = CALLBACK_REPLY,
                accessibilityAvailable = available,
                screenShotSuccess = success,
                screenShotImage = imageUrl,
            )
            runCatching { imClient.sendCommand(requesterId, CmdType.SCREEN_SHOT, payload.toJson()) }
        }
    }

    /** 发起端：请求一帧。 */
    fun request(peerUserId: Long) {
        scope.launch {
            val payload = CmdScreenShot(userid = myUserId(), step = 1, callBack = CALLBACK_REQUEST)
            runCatching { imClient.sendCommand(peerUserId, CmdType.SCREEN_SHOT, payload.toJson()) }
        }
    }

    /** 发起端：step3 落台账（`api/screenshot/done?friendId&pic`）。 */
    fun reportDone(peerUserId: Long, picUrl: String) {
        scope.launch {
            runCatching { api.screenshotDone(friendId = peerUserId, q1 = picUrl) }
        }
    }

    private fun writePng(bitmap: Bitmap): File? = runCatching {
        val dir = File(context.cacheDir, "screenshot").apply { mkdirs() }
        val file = File(dir, "shot-${System.currentTimeMillis()}.png")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        file
    }.getOrNull()

    private companion object {
        private const val TAG = "ScreenshotBridge"
        const val CALLBACK_REQUEST = 0
        const val CALLBACK_REPLY = 1
    }
}
