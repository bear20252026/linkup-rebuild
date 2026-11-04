package com.mc.fastkit.utils

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.view.ViewTreeObserver
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Rebuilt from the decompiled `com.mc.fastkit.utils.KeyboardUtils`. The original also owned a popup
 * manager, an IME-provider fallback and status-bar work; only what the recovered screens call is
 * reproduced here, and the two magic numbers are the original's own thresholds.
 */
object KeyboardUtils {

    /** How much the visible frame has to shrink before the keyboard counts as up (original default). */
    var showThresholdPx: Int = 250

    /** Listener `b` of the original: `onKeyboardChanged(visible, heightDeltaPx)`. */
    fun interface Listener {
        fun onKeyboardChanged(visible: Boolean, heightPx: Int)
    }

    private const val HIDE_THRESHOLD_PX = 200
    private const val TAG_WATCHER = -8

    fun hideKeyboard(view: View) {
        val manager = view.context.getSystemService(InputMethodManager::class.java) ?: return
        manager.hideSoftInputFromWindow(view.windowToken, 0)
    }

    fun isKeyboardShown(activity: Activity): Boolean =
        belowVisibleFrame(activity.window) > showThresholdPx

    /** The recovered global-layout rule, removed again when the host lifecycle ends. */
    fun register(activity: Activity, listener: Listener) {
        val decor = activity.window.decorView
        val watcher = LayoutWatcher(activity.window, listener)
        decor.setTag(TAG_WATCHER, watcher)
        decor.viewTreeObserver.addOnGlobalLayoutListener(watcher)
        if (activity is LifecycleOwner) {
            activity.lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onDestroy(owner: LifecycleOwner) = unregister(activity)
                },
            )
        }
    }

    fun unregister(activity: Activity) {
        val decor = activity.window.decorView
        (decor.getTag(TAG_WATCHER) as? LayoutWatcher)?.let { watcher ->
            decor.viewTreeObserver.removeOnGlobalLayoutListener(watcher)
            decor.setTag(TAG_WATCHER, null)
        }
    }

    private fun belowVisibleFrame(window: Window): Int {
        val decor = window.decorView
        val visible = Rect()
        decor.getWindowVisibleDisplayFrame(visible)
        return Math.abs(decor.bottom - visible.bottom)
    }

    private class LayoutWatcher(
        private val window: Window,
        private val listener: Listener,
    ) : ViewTreeObserver.OnGlobalLayoutListener {

        private var lastVisibleHeight = 0

        override fun onGlobalLayout() {
            val visible = Rect()
            window.decorView.getWindowVisibleDisplayFrame(visible)
            val height = visible.height()
            if (lastVisibleHeight == 0) {
                lastVisibleHeight = height
                return
            }
            if (lastVisibleHeight == height) return
            if (lastVisibleHeight - height > showThresholdPx) {
                listener.onKeyboardChanged(true, lastVisibleHeight - height)
                lastVisibleHeight = height
                return
            }
            if (height - lastVisibleHeight > HIDE_THRESHOLD_PX) {
                listener.onKeyboardChanged(false, height - lastVisibleHeight)
                lastVisibleHeight = height
            }
        }
    }
}
