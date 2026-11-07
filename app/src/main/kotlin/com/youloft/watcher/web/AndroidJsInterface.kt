package com.youloft.watcher.web

import android.content.Context
import android.webkit.JavascriptInterface
import com.youloft.watcher.data.LinkUpApplication

/**
 * `com.youloft.watcher.web.m`（addJavascriptInterface(..., "androidJS") 的对象）逐行重建：
 * 状态栏高度与 VIP 态两个同步取值方法。getContext 原版也暴露，保留（H5 不该拿到敏感面，
 * Context 本身就是容器给的）。
 */
class AndroidJsInterface(
    private val context: Context,
    private val graph: () -> LinkUpApplication,
) {
    @JavascriptInterface
    fun getStatusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    /** `CacheUtils.B0().vipType > 0` — 缓存账户记录的 VIP 态。 */
    @JavascriptInterface
    fun getVipStatus(): Boolean = graph().isVip

    @JavascriptInterface
    fun getContext(): Context = context
}
