package com.youloft.webview

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Recovered API surface of the H5 container view. Layouts declare the component itself with
 * `android:id="@id/webView"`, so the child WebView is created here rather than in XML.
 */
class WebComponent @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    val webView: CommonWebView = CommonWebView(context)

    init {
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun url(): String = webView.url.orEmpty()

    fun originalUrl(): String = webView.url.orEmpty()

    fun title(): String = webView.pageTitle()

    fun activity(): Activity? = context as? Activity

    fun loadUrl(url: String) {
        webView.loadUrl(url)
    }
}
