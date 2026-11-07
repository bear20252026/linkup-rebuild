package com.youloft.watcher.utils

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.youloft.watcher.bean.lock.LockScreen
import com.youloft.watcher.view.lock.LockScreenView

/**
 * `utils/WindowHelper` 的重建位（整手机锁 J 路径）：WindowManager `TYPE_APPLICATION_OVERLAY`
 * (2038) 全屏悬浮窗盖在任意应用上；无 overlay 权限时 [canShow] 答 false，调用方降级到应用内
 * LockedActivity（原版同刻打日志"[锁机]：无悬浮窗权限"）。
 *
 * 与原版的偏离如实记录（DESIGN）：
 * - 应用锁 G 路径（alpha 0/1 随前台应用切换）需要 `LockScreen.appList` + UsageStats 权限，
 *   本工程的命令载荷还没有 appList 写入方，整手机锁先落地。
 * - lockEndTime 的落盘恢复（原版 CacheUtils.s2/T）由命令桥每次直发，不需要本类存状态。
 */
object LockOverlayWindow {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var view: LockScreenView? = null
    private var params: WindowManager.LayoutParams? = null

    /** `WindowHelper.w` — SYSTEM_ALERT_WINDOW 是否已授权。 */
    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 跳系统"显示在其他应用上层"授权页（`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`）。 */
    fun openSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** `WindowHelper.J` — 全屏不透明盖板；已在锁中时保持现有锁（原版同判空）。 */
    fun show(context: Context, screen: LockScreen, onUnlock: (LockScreen) -> Unit) {
        mainHandler.post {
            if (view != null) return@post
            val windowManager = context.getSystemService(WindowManager::class.java) ?: return@post
            val lockView = LockScreenView(context).apply {
                setData(
                    screen,
                    onUnlock = { unlocked ->
                        onUnlock(unlocked)
                        dismiss(context)
                    },
                    onNeedsInput = { focusForInput(context) },
                )
            }
            val metrics = windowManager.maximumWindowMetrics.bounds
            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, // 原版 2038
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS, // 原版 0x80000308
                PixelFormat.OPAQUE, // 原版 format = 1
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                width = metrics.width()
                height = metrics.height()
                x = 0
                y = 0
                alpha = 1f // 整手机锁：J 路径常亮
            }
            runCatching { windowManager.addView(lockView, layoutParams) }
                .onFailure { return@post }
            view = lockView
            params = layoutParams
        }
    }

    /**
     * 答案框要键盘：摘 NOT_FOCUSABLE、开软键盘（原版 E/F 换 flags 的同族操作；换回由 dismiss
     * 兜底——盖板摘除即无焦点残留）。
     */
    fun focusForInput(context: Context) {
        mainHandler.post {
            val current = view ?: return@post
            val layoutParams = params ?: return@post
            val windowManager = context.getSystemService(WindowManager::class.java) ?: return@post
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            layoutParams.softInputMode =
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            runCatching { windowManager.updateViewLayout(current, layoutParams) }
        }
    }

    /** `WindowHelper.C` — 摘除盖板；未在锁中是幂等 no-op。 */
    fun dismiss(context: Context) {
        mainHandler.post {
            val current = view ?: return@post
            val windowManager = context.getSystemService(WindowManager::class.java) ?: return@post
            runCatching { windowManager.removeView(current) }
            view = null
            params = null
        }
    }

    /** 测试口：当前是否已有盖板在窗。 */
    fun isShowing(): Boolean = view != null
}
