package com.youloft.watcher.pages.web

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.youloft.watcher.databinding.ActivityCommonWebBinding

/**
 * The in-app browser every agreement link opens. The recovered `CommonWebActivity` is a large H5 host
 * (JS bridge, auth hand-off, protocol dispatcher); those are the H5 container's own card. What the login
 * and privacy gates provably need is the plain document path: load a URL, show a title, a loading and an
 * error state, and a back arrow. The privacy gate opens it with `fullscreen=true` and no auth parameters.
 */
class CommonWebActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCommonWebBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommonWebBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!isWebUrl(url)) {
            finish()
            return
        }
        binding.tvTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (intent.getBooleanExtra(EXTRA_FULLSCREEN, false)) binding.tvTitle.visibility = View.GONE
        binding.ivBack.setOnClickListener { finish() }
        binding.tvText.setOnClickListener { binding.webView.loadUrl(url) }

        binding.webView.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        binding.webView.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                binding.stateLoading.visibility = View.VISIBLE
                binding.stateError.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.stateLoading.visibility = View.GONE
                if (binding.tvTitle.text.isEmpty()) binding.tvTitle.text = view?.title.orEmpty()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    binding.stateLoading.visibility = View.GONE
                    binding.stateError.visibility = View.VISIBLE
                }
            }
        }
        binding.webView.loadUrl(url)
    }

    override fun onDestroy() {
        binding.webView.webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FULLSCREEN = "fullscreen"

        /** `ag.a.b()` — the H5 base, distinct from the API gateway. */
        const val WEB_BASE = "https://web-link.tttick.com/"

        /** `CommonWebActivity.A` / `.C`, opened by the privacy gate without auth parameters. */
        const val PRIVACY_URL = WEB_BASE + "business/protocol/privacy.html?fullscreen=true"
        const val USER_AGREEMENT_URL = WEB_BASE + "business/protocol/user.html?fullscreen=true"

        fun start(context: Context, url: String, title: String = "", fullscreen: Boolean = false) {
            context.startActivity(
                Intent(context, CommonWebActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_FULLSCREEN, fullscreen),
            )
        }

        /** Only http(s) documents; never file/content/javascript schemes from an external string. */
        fun isWebUrl(url: String): Boolean {
            val scheme = Uri.parse(url).scheme?.lowercase()
            return scheme == "https" || scheme == "http"
        }
    }
}