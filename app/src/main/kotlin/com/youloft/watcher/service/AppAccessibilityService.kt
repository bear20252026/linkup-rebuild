package com.youloft.watcher.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executor

/**
 * `service/AppAccessibilityService` 逐行重建：本 app 的无障碍服务只为一件事存在——
 * `AccessibilityService.takeScreenshot`（API 30+，实时截屏命令的采集端）。事件回调全空
 * （清单 `canRetrieveWindowContent=false`，与原版一致：不读屏内容，只拿帧）。
 *
 * 授权由系统无障碍设置页完成（`accessibility_service_config.xml` `canTakeScreenshot=true`）；
 * 连上时 [instance] 置位，断开/解绑清空——[Companion.takeScreenshot] 据此答"无权限"。
 */
class AppAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        var instance: AppAccessibilityService? = null
            private set

        /** 服务已连上且系统支持 takeScreenshot（API 30+）。 */
        @SuppressLint("AnnotateVersionCheck")
        fun canTakeScreenshot(): Boolean = instance != null && Build.VERSION.SDK_INT >= 30

        /**
         * 采一帧。不可用/失败都走 `callback(null)`——命令桥把它翻成 step 回执。hardware buffer
         * 在 wrap 之后立即归还（原版同序）。
         */
        @SuppressLint("AnnotateVersionCheck")
        fun takeScreenshot(callback: (Bitmap?) -> Unit) {
            val service = instance
            if (service == null || Build.VERSION.SDK_INT < 30) {
                callback(null)
                return
            }
            val mainExecutor = Executor { runnable -> Handler(Looper.getMainLooper()).post(runnable) }
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            callback(
                                runCatching {
                                    Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                }.getOrNull(),
                            )
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        callback(null)
                    }
                },
            )
        }
    }
}
