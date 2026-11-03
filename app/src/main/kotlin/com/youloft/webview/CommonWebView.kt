package com.youloft.webview

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap

/**
 * The H5 container's WebView. The recovered original also owns a JS bridge and a protocol
 * dispatcher; those are whole subsystems and are deliberately left to the H5 container card
 * rather than stubbed here, so only the state the call sites provably rely on exists:
 * a per-page data bag and the document title.
 */
class CommonWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle,
) : WebView(context, attrs, defStyleAttr) {

    private val storeData = ConcurrentHashMap<String, Any>()

    fun put(key: String, value: Any) {
        storeData[key] = value
    }

    fun <T> take(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return storeData.remove(key) as? T
    }

    fun pageTitle(): String = title.orEmpty()
}
