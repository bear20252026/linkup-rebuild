package com.youloft.watcher.ext

import android.view.View

/**
 * The recovered click helpers (`com.mc.fastkit.ext.g0.O` and `com.youloft.watcher.ext.r.c`) both
 * install a click listener that ignores repeats inside an interval; `r.c` additionally plays the
 * platform click feedback. This is that behaviour, one helper instead of two obfuscated entry points.
 */
internal fun View.onIntervalClick(intervalMillis: Long = DEFAULT_INTERVAL_MILLIS, action: (View) -> Unit) {
    var lastClickAt = 0L
    setOnClickListener { view ->
        val now = System.currentTimeMillis()
        if (now - lastClickAt < intervalMillis) return@setOnClickListener
        lastClickAt = now
        action(view)
    }
}

private const val DEFAULT_INTERVAL_MILLIS = 500L
