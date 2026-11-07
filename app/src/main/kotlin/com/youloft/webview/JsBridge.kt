package com.youloft.webview

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * `jh.f`（JavaScriptBridge）的重建：H5 → 原生的同步取值通道。
 *
 * 原版机制逐条保留：页面可见时注入 `window._eval`（eval 包一层，异步结果经 `confirm('_ret_<seq>_<json>')`
 * 回传）；`evaluateJavascript` 的调用在注入完成前排进队列；`call(name, args...)` 拼的是
 * `name('a', 1, true)` 形式的调用串——字符串单引号、数字/布尔裸写、其余 JSON 序列化。
 */
class JsBridge(private val webView: WebView) {

    private var injected = false
    private val pending = ConcurrentLinkedQueue<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** `jh.f.h` — 页面可见：注入 _eval，300ms 后放队列。 */
    fun onPageCommitVisible() {
        mainHandler.post {
            webView.loadUrl(
                "javascript:(function(){window._eval=function(js,_reqcode){var ret=undefined;" +
                    "try{ret=eval(js)}catch(e){ret='exception'}" +
                    "if(_reqcode){setTimeout(function(){confirm('_ret_'+_reqcode+'_'+ " +
                    "(ret==undefined?'':JSON.stringify(ret)))},200)}}})()",
            )
            injected = true
        }
        mainHandler.postDelayed({ drain() }, 300)
    }

    /** `jh.f.i` — 新页面开始：注入标记复位，队列重新等。 */
    fun onPageStarted() {
        injected = false
    }

    /** 在主线程求值一段 JS；未注入时排队。 */
    fun evaluate(script: String, onResult: ((String?) -> Unit)? = null) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            evaluateNow(script, onResult)
        } else {
            mainHandler.post { evaluateNow(script, onResult) }
        }
    }

    private fun evaluateNow(script: String, onResult: ((String?) -> Unit)?) {
        if (!injected) {
            pending += { evaluateNow(script, onResult) }
            return
        }
        webView.evaluateJavascript(script) { value ->
            onResult?.invoke(value?.takeIf { !it.equals("null", ignoreCase = true) } ?: "")
        }
    }

    private fun drain() {
        if (!injected) return
        while (true) {
            val next = pending.poll() ?: break
            runCatching(next)
        }
    }

    /** `jh.f.j` — `_ret_<seq>_<json>` 的回执匹配点（本重建直接回调，不再走 confirm 往返）。 */
    fun onReturn(seq: String, value: String?) {
        // 队列化求值路径无需 seq 匹配；保留原版入口给 ChromeClient 的 confirm 分支调。
    }

    /** `jh.f.g` — 拼 `name(arg1, arg2)`；字符串加单引号，数字/布尔裸写，其余走 JSON。 */
    fun call(name: String, args: List<Any?>, onResult: ((String?) -> Unit)? = null) {
        evaluate(buildCallScript(name, args), onResult)
    }

    companion object {
        fun buildCallScript(name: String, args: List<Any?>): String =
            name + "(" + args.joinToString(",") { arg ->
                when (arg) {
                    is String -> "'" + arg.replace("'", "\\'") + "'"
                    is Boolean, is Int, is Long, is Float, is Double -> arg.toString()
                    null -> "''"
                    else -> com.google.gson.Gson().toJson(arg)
                }
            } + ")"
    }
}
