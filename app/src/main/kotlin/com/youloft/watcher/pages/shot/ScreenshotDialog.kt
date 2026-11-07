package com.youloft.watcher.pages.shot

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.youloft.linkup.api.dto.ScreenshotData
import com.youloft.watcher.R
import com.youloft.watcher.bean.CmdScreenShot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 发起端截屏弹窗（原版 `pages/shot/ScreenshotDialog` + `j0` 状态机的重建）：
 * 三步推进（发指令 → 采集中 → 加载图片），统一 22 秒超时（原版"[截屏]:发送截屏指令，统一超时22s"）。
 * 成功 = step3 带图 URL → Glide 加载预览 + `api/screenshot/done` 台账；对方无障碍未开 = step1 答
 * false，直接失败态。原版三枚 lottie 步进动画换成本工程的步进文字条（DESIGN 记录）。
 */
class ScreenshotDialog(
    context: Context,
    private val row: ScreenshotData,
    private val launcher: ScreenshotCommandBridgeLauncher,
    private val scope: CoroutineScope,
) : Dialog(context) {

    /** 宿主注入的两个动作（由 Activity 持有的真桥实现；测试可换）。 */
    interface ScreenshotCommandBridgeLauncher {
        fun request(peerUserId: Long)
        fun reportDone(peerUserId: Long, picUrl: String)
    }

    private val statusText = TextView(context).apply {
        setTextColor(0xFF324058.toInt())
        textSize = 15f
        gravity = Gravity.CENTER
    }
    private val stepBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val preview = ImageView(context).apply {
        visibility = android.view.View.GONE
        adjustViewBounds = true
    }
    private val retry = Button(context).apply {
        setText(R.string.shot_retry)
        visibility = android.view.View.GONE
    }
    private var timeoutJob: Job? = null
    private var collectJob: Job? = null

    override fun show() {
        setContentView(buildContent())
        setCancelable(false)
        super.show()
        start()
    }

    private fun buildContent(): android.view.View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 32)
            addView(
                TextView(context).apply {
                    text = row.nickname ?: row.userId.toString()
                    textSize = 16f
                    setTextColor(0xFF324058.toInt())
                    gravity = Gravity.CENTER
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                stepBar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 24 },
            )
            for (label in listOf("1", "2", "3")) {
                stepBar.addView(
                    TextView(context).apply {
                        text = label
                        gravity = Gravity.CENTER
                        textSize = 13f
                        setTextColor(Color.WHITE)
                        tag = "step$label"
                        setBackgroundResource(R.drawable.bg_home_card)
                        background.setTint(0xFFC7C9CE.toInt())
                    },
                    LinearLayout.LayoutParams(0, 96, 1f).apply {
                        marginEnd = if (label != "3") 16 else 0
                    },
                )
            }
            addView(
                statusText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 32 },
            )
            addView(
                preview,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 24 },
            )
            addView(
                retry,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 24 },
            )
            retry.setOnClickListener { start() }
            addView(
                Button(context).apply {
                    setText(R.string.confirm)
                    setOnClickListener { dismiss() }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 8 },
            )
        }

    /** 发起一轮：复位步进 → 指令 → 订阅回执 → 22 秒统一超时。 */
    private fun start() {
        timeoutJob?.cancel()
        collectJob?.cancel()
        preview.visibility = android.view.View.GONE
        retry.visibility = android.view.View.GONE
        setStep(1)
        statusText.setText(R.string.screenshot_sending)
        launcher.request(row.userId)
        collectJob = (context as? ScreenshotReplies)?.screenshotReplies
            ?.onEach { onReply(it) }
            ?.launchIn(scope)
            ?: scope.launch { /* 宿主未接回执流（不应发生）：仅超时兜底 */ }
        timeoutJob = scope.launch {
            delay(TIMEOUT_MILLIS)
            fail(context.getString(R.string.screenshot_timeout))
        }
    }

    private fun onReply(reply: CmdScreenShot) {
        when {
            reply.step == 1 && !reply.accessibilityAvailable ->
                fail(context.getString(R.string.screenshot_peer_no_permission))
            reply.step == 2 -> {
                setStep(2)
                statusText.setText(R.string.screenshot_capturing)
                if (!reply.screenShotSuccess) {
                    fail(context.getString(R.string.shot_failed))
                }
            }
            reply.step == 3 -> {
                val url = reply.screenShotImage
                if (url.isNullOrBlank()) {
                    fail(context.getString(R.string.shot_failed))
                } else {
                    succeed(url)
                }
            }
        }
    }

    private fun succeed(url: String) {
        timeoutJob?.cancel()
        setStep(3)
        statusText.setText(R.string.screenshot_loading_image)
        preview.visibility = android.view.View.VISIBLE
        Glide.with(context).load(url).into(preview)
        launcher.reportDone(row.userId, url)
    }

    private fun fail(message: String) {
        timeoutJob?.cancel()
        statusText.text = message
        retry.visibility = android.view.View.VISIBLE
    }

    /** 步进条染色：到第 n 步把 1..n 全部点亮。 */
    private fun setStep(step: Int) {
        for (index in 1..3) {
            stepBar.findViewWithTag<TextView>("step$index")?.background?.setTint(
                if (index <= step) 0xFF324058.toInt() else 0xFFC7C9CE.toInt(),
            )
        }
    }

    override fun dismiss() {
        timeoutJob?.cancel()
        collectJob?.cancel()
        super.dismiss()
    }

    /** 原版 j0 的统一超时常量。 */
    private companion object {
        const val TIMEOUT_MILLIS = 22_000L
    }
}

/** 宿主 Activity 实现它，把弹窗接上桥的回执流（避免 Dialog 直接持桥）。 */
interface ScreenshotReplies {
    val screenshotReplies: kotlinx.coroutines.flow.SharedFlow<CmdScreenShot>
}
