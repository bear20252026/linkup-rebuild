package com.youloft.webview

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ConcurrentHashMap

/**
 * The H5 container's WebView（原版库 `com.youloft.webview.CommonWebView` 的重建位）。
 *
 * 本轮把拦截器链装上：[JsBridge]（evaluateJavascript 队列 + `_eval` 注入）、
 * [ProtocolDispatcher]（`protocol://` 三入口分发）、自持的 WebViewClient/WebChromeClient
 * （`jh.d`/`jh.c` 的拦截顺序），宿主回调走 [WebViewInterceptor]。此前"只有数据袋与标题"的
 * 空壳状态（DESIGN 五点二十的占位记录）就此关闭。
 */
class CommonWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle,
) : WebView(context, attrs, defStyleAttr) {

    private val storeData = ConcurrentHashMap<String, Any>()

    val jsBridge = JsBridge(this)
    val protocolDispatcher = ProtocolDispatcher { envelope ->
        interceptor?.dispatchCommand(this, envelope.method, envelope.args) ?: UNSUPPORTED
    }

    private var interceptor: WebViewInterceptor? = null

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            // 原版 jh.d 的拦截序：宿主 S → 协议分发 → mailto → http(s) 重写后重载 → 其余交系统。
            if (interceptor?.shouldOverrideUrlLoading(view, url) == true) return true
            if (protocolDispatcher.matches(url)) {
                view.post { protocolDispatcher.dispatchUrl(url) }
                return true
            }
            if (url.startsWith("mailto:", ignoreCase = true)) {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse(url))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                return true
            }
            val scheme = url.substringBefore("://", "").lowercase()
            if (scheme == "http" || scheme == "https") {
                val rewritten = interceptor?.rewriteUrl(url) ?: url
                if (rewritten.equals(url, ignoreCase = true)) return false
                view.post { view.loadUrl(rewritten) }
                return true
            }
            // 其余 scheme（weixin:// 等）：原版起 VIEW 意图；本重建同样交系统，失败静默。
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            jsBridge.onPageStarted()
            interceptor?.onPageStarted(view, url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            interceptor?.onPageFinished(view, url)
        }

        @Deprecated("Deprecated in Java")
        override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
            interceptor?.onReceivedError(view, errorCode, description, failingUrl)
            super.onReceivedError(view, errorCode, description, failingUrl)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            jsBridge.onPageCommitVisible()
        }
    }

    private val chromeClient = object : WebChromeClient() {
        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            // `_ret_` 是 JsBridge 的回执通道；`protocol:` 是 H5 的同步确认式调用。
            if (message != null && message.startsWith(BridgeContracts.CONFIRM_RETURN_PREFIX)) {
                val body = message.removePrefix(BridgeContracts.CONFIRM_RETURN_PREFIX)
                val seq = body.substringBefore("_")
                val value = body.substringAfter("_", "")
                jsBridge.onReturn(seq, value)
                result.confirm()
                return true
            }
            if (message != null && protocolDispatcher.matches(message)) {
                protocolDispatcher.dispatchUrl(message)
                result.confirm()
                return true
            }
            return super.onJsConfirm(view, url, message, result)
        }

        override fun onJsPrompt(
            view: WebView,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: android.webkit.JsPromptResult,
        ): Boolean {
            if (!message.orEmpty().startsWith(BridgeContracts.PROMPT_INVOKE_PREFIX)) {
                return super.onJsPrompt(view, url, message, defaultValue, result)
            }
            val answer = runCatching {
                com.google.gson.Gson().toJson(
                    protocolDispatcher.parse(message.orEmpty().removePrefix(BridgeContracts.PROMPT_INVOKE_PREFIX))
                        .let { envelope -> interceptor?.dispatchCommand(view, envelope.method, envelope.args) }
                        ?: UNSUPPORTED,
                )
            }.getOrDefault("\"$UNSUPPORTED\"")
            result.confirm(answer)
            return true
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            interceptor?.onReceivedTitle(view, title)
        }
    }

    init {
        @SuppressLint("SetJavaScriptEnabled")
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webViewClient = client
        webChromeClient = chromeClient
    }

    /** 原版 setWebViewInterceptor/setWebInterceptor；命令分发入口在这里接到宿主的 g0。 */
    fun setWebInterceptor(newInterceptor: WebViewInterceptor) {
        interceptor = newInterceptor
    }

    fun put(key: String, value: Any) {
        storeData[key] = value
    }

    fun <T> take(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return storeData.remove(key) as? T
    }

    fun pageTitle(): String = title.orEmpty()

    private companion object {
        /** 原版 `jh.h.b` 缺省答 `-unsupport-`。 */
        const val UNSUPPORTED = "-unsupport-"
    }
}
