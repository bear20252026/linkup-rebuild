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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.ActivityCommonWebBinding
import com.youloft.watcher.web.AndroidJsInterface
import com.youloft.watcher.web.CommonInterceptor
import com.youloft.watcher.web.PicturePickingHost
import com.youloft.webview.BridgeContracts

/**
 * The in-app H5 host（原版 `CommonWebActivity` 的重建位）。本轮把拦截器链接上：协议分发
 * （`protocol://` 三入口）+ `androidJS` 同步对象 + [CommonInterceptor] 的 13 个 action（待验
 * 卡合龙）；隐私/协议页仍走纯文档路径（fullscreen、无鉴权参数）。
 */
class CommonWebActivity : AppCompatActivity(), PicturePickingHost {

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

        val graph = appGraph(this)
        // 拦截器链（原版 setWebInterceptor(new d(...)) + addJavascriptInterface(m, "androidJS")）。
        binding.webView.webView.setWebInterceptor(
            CommonInterceptor(
                hostActivity = this,
                scope = lifecycleScope,
                webBinding = object : CommonInterceptor.CommonWebBinding {
                    override val backButton: View? get() = binding.ivBack
                    override val stateLoading: View? get() = binding.stateLoading
                    override val stateError: View? get() = binding.stateError
                    override val retryButton: View? get() = binding.tvText
                },
                graphProvider = { graph },
            ),
        )
        binding.webView.webView.addJavascriptInterface(
            AndroidJsInterface(this) { graph },
            BridgeContracts.JS_OBJECT_NAME,
        )
        binding.tvText.setOnClickListener { binding.webView.webView.reload() }
        binding.webView.loadUrl(url)
    }

    /** selectPictures 的相册选择器（PickVisualMedia，免读权限）；maxImages 夹在系统上限 9 内。 */
    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
            pendingPick?.invoke(uris)
            pendingPick = null
        }

    private val pickSingle =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            pendingPick?.invoke(listOfNotNull(uri))
            pendingPick = null
        }

    private var pendingPick: ((List<Uri>) -> Unit)? = null

    override fun pickImages(maxImages: Int, onResult: (List<Uri>) -> Unit) {
        pendingPick = onResult
        val request = androidx.activity.result.PickVisualMediaRequest(
            ActivityResultContracts.PickVisualMedia.ImageOnly,
        )
        if (maxImages <= 1) pickSingle.launch(request) else pickMedia.launch(request)
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