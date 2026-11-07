package com.youloft.webview

import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebView

/**
 * `jh.j`（WebInterceptor）的重建面：CommonWebView 的宿主回调 + 命令分发入口。取原版接口里
 * 本工程真正会走的子集（下载管理/R11 长按存图等窗口族留到对应卡），方法名对齐语义：
 * S=shouldOverrideUrlLoading、A=onPageStarted（延迟出 loading）、D=onPageFinished、
 * I=onReceivedError、L=onReceivedTitle、Q=url 重写、g=协议命令分发（g0 的入口）。
 */
interface WebViewInterceptor {
    /** 宿主 Activity（原版 getActivity）。 */
    val activity: android.app.Activity?

    /** `jh.j.S` — URL 拦截；true = 已处理。 */
    fun shouldOverrideUrlLoading(webView: WebView, url: String): Boolean = false

    /** `jh.j.A` — 页面开始（500ms 后仍没结束则露出错误态是宿主的实现）。 */
    fun onPageStarted(webView: WebView, url: String) {}

    /** `jh.j.D`。 */
    fun onPageFinished(webView: WebView, url: String) {}

    /** `jh.j.I` — 主文档失败。 */
    fun onReceivedError(webView: WebView, errorCode: Int, description: String?, failingUrl: String?) {}

    /** `jh.j.L` — 标题（原版走 CommonWebView.m 的固定标题通道，这里同步给宿主）。 */
    fun onReceivedTitle(webView: WebView, title: String?) {}

    /** `jh.j.Q` — 加载前的 URL 重写（原版 ext.m.c 注入公共查询参数）。 */
    fun rewriteUrl(url: String): String = url

    /** `jh.j.g`/`g0` — 协议命令分发；answer 同步回 prompt 调用方。 */
    fun dispatchCommand(webView: WebView, command: String, args: Map<String, String>): Any? = null

    /** 原版 `jh.j.q`（下载回调族）与 `jh.j.M`（进度条）留给下载卡，默认空。 */
    fun onDownloadStart(context: Context, url: String, mimeType: String?) {}
}

/** `jh.c` 的 `_ret_`/`protocol:`/`__invoke__` 三个拦截点在 ChromeClient 里的语义常量。 */
internal object BridgeContracts {
    const val JS_OBJECT_NAME = "androidJS"
    const val PROMPT_INVOKE_PREFIX = "__invoke__"
    const val CONFIRM_RETURN_PREFIX = "_ret_"
    const val SCHEMA = "protocol"
}
