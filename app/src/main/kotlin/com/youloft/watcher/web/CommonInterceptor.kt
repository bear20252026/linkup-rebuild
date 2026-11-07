package com.youloft.watcher.web

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.WebView
import android.widget.Toast
import com.youloft.linkup.im.CmdType
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.pages.web.CommonWebActivity
import com.youloft.webview.H5Commands
import com.youloft.webview.JsBridge
import com.youloft.webview.WebViewInterceptor
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * `com.youloft.watcher.web.d`（CommonInterceptor，582 行）的重建：13 个 action 的执行层。
 * 路由在 [H5Commands]（纯函数、JVM 可测），这里只做 Android 副作用。
 *
 * 如实标注的执行取舍（原版行为 → 本重建）：
 * - `permissionTip` 族：原版发环信 CMD `chatAuth` 子类型并按发送结果 toast；本重建经 OpenIM
 *   发 `update_permissions`（载荷只带 type——其余键不可恢复），toast 按发送成功与否。
 * - `selectPictures`：原版选图后上传 TOS 回 URL 列表；本重建回本地 cache 路径列表（上传链是
 *   IM 媒体那条，H5 拿 URL 的语义已由 saveMediaFile 的下载侧对齐）。
 * - `saveMediaFile`：下载进公共媒体库（MediaStore），回 `{status:bool, errorList:[...]}` 同原版。
 * - `sendRecoverMessage`：userid + type(1→18/19)，与 permissionTip 同一条 update_permissions 通道。
 * - `jumpPage`：只路由 http(s)（进 H5 容器）；其余 scheme 不猜。
 * - `dataTrackEvent`：埋点 SDK 随源码丢失，落 logcat（tag H5Track）。
 * - `joinQQGroup`：原版 mqqapi 深链常量不可恢复，用 qm.qq.com 加群页 + 失败 toast 兜底。
 * - `onShowMemberPop`：会员购买弹窗未重建，记日志空实现。
 */
class CommonInterceptor(
    private val hostActivity: Activity,
    private val scope: CoroutineScope,
    webBinding: CommonWebBinding,
    private val graphProvider: () -> com.youloft.watcher.data.LinkUpApplication,
) : WebViewInterceptor {

    /** 宿主屏的视图面（ActivityCommonWebBinding 的子集），避免本类直接持 ViewBinding 类型。 */
    interface CommonWebBinding {
        val backButton: View?
        val stateLoading: View?
        val stateError: View?
        val retryButton: View?
    }

    private val okHttp = OkHttpClient()
    private val binding = webBinding

    override val activity: Activity get() = hostActivity

    override fun shouldOverrideUrlLoading(webView: WebView, url: String): Boolean = false

    override fun onPageStarted(webView: WebView, url: String) {
        binding.stateLoading?.visibility = View.VISIBLE
        binding.stateError?.visibility = View.GONE
    }

    override fun onPageFinished(webView: WebView, url: String) {
        binding.stateLoading?.visibility = View.GONE
    }

    override fun onReceivedError(webView: WebView, errorCode: Int, description: String?, failingUrl: String?) {
        binding.stateLoading?.visibility = View.GONE
        binding.stateError?.visibility = View.VISIBLE
    }

    override fun onReceivedTitle(webView: WebView, title: String?) {
        // 原版固定标题由 CommonWebView.m 通道承担；容器标题行为留给宿主屏。
    }

    override fun rewriteUrl(url: String): String {
        // 原版 ext.m.c 注入 token/userid 等公共参数；隐私协议页不带鉴权参数（原版同路径），
        // 其余页追加 userid/token（CommonWebActivity.e 的公共串子集）。
        if (!url.startsWith("http")) return url
        val app = graphProvider()
        val user = app.users.current()
        if (user.userId <= 0) return url
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}userid=${user.userId}&token=${user.token.orEmpty()}&av=1.6.6&client=android"
    }

    override fun dispatchCommand(webView: WebView, command: String, args: Map<String, String>): Any? {
        return when (val intent = H5Commands.route(command, args)) {
            is H5Commands.Intent.OpenH5 -> {
                CommonWebActivity.start(hostActivity, intent.url)
                null
            }
            is H5Commands.Intent.ReadClipboard -> {
                val text = clipboardText()
                JsBridge.buildCallScript(intent.callback, listOf(text))
                    .also { script -> webView.post { webView.evaluateJavascript(script, null) } }
                null
            }
            is H5Commands.Intent.PermissionTip -> {
                sendPermissionCmd(intent.chatAuthType)
                null
            }
            H5Commands.Intent.RefreshVip -> {
                scope.launch { runCatching { graphProvider().users.refresh() } }
                null
            }
            is H5Commands.Intent.JumpPage -> {
                if (intent.scheme.startsWith("http")) {
                    CommonWebActivity.start(hostActivity, intent.scheme)
                }
                null
            }
            is H5Commands.Intent.ToggleBackButton -> {
                binding.backButton?.visibility = if (intent.show) View.VISIBLE else View.GONE
                null
            }
            is H5Commands.Intent.Toast -> {
                Toast.makeText(hostActivity, intent.message, Toast.LENGTH_SHORT).show()
                null
            }
            is H5Commands.Intent.SaveMedia -> {
                saveMedia(webView, intent)
                null
            }
            is H5Commands.Intent.SelectPictures -> {
                selectPictures(webView, intent)
                null
            }
            is H5Commands.Intent.SendRecoverMessage -> {
                sendPermissionCmd(intent.cmdType, peerId = intent.userId)
                null
            }
            is H5Commands.Intent.TrackEvent -> {
                android.util.Log.i("H5Track", "event=${intent.event} once=${intent.isOnce} props=${intent.properties}")
                null
            }
            is H5Commands.Intent.JoinQQGroup -> {
                joinGroup(intent)
                null
            }
            is H5Commands.Intent.MemberPop -> {
                android.util.Log.i("H5Track", "memberPop source=${intent.source} banner=${intent.bannerName}")
                null
            }
            H5Commands.Intent.Ignored, H5Commands.Intent.Unknown -> null
        }
    }

    private fun clipboardText(): String {
        val manager = hostActivity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        val item = manager.primaryClip?.getItemAt(0) ?: return ""
        return item.text?.toString() ?: ""
    }

    private fun sendPermissionCmd(chatAuthType: Int, peerId: Long = 0L) {
        val app = graphProvider()
        val target = if (peerId > 0) peerId else app.users.current().mainUser
        scope.launch {
            val sent = runCatching {
                app.imClient.sendCommand(
                    target,
                    CmdType.UPDATE_PERMISSIONS,
                    """{"key":"chatAuth","type":$chatAuthType}""",
                )
            }.isSuccess
            Toast.makeText(
                activity,
                if (sent) R.string.send_success else R.string.send_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** 下载 URL 列表进媒体库，回调 `{status, errorList}`（原版 saveMediaFile 的回执形）。 */
    private fun saveMedia(webView: WebView, intent: H5Commands.Intent.SaveMedia) {
        scope.launch {
            val failures = mutableListOf<String>()
            for (url in intent.list) {
                val saved = runCatching { downloadToMediaStore(url, intent.type) }.getOrDefault(false)
                if (!saved) failures += url
            }
            if (intent.callback.isNotBlank()) {
                val payload = if (failures.isEmpty()) {
                    "{\"status\":true}"
                } else {
                    "{\"status\":false,\"errorList\":" +
                        failures.joinToString(",", "[", "]") { "\"$it\"" } + "}"
                }
                hostActivity.runOnUiThread {
                    webView.evaluateJavascript(intent.callback + "(" + payload + ")", null)
                }
            }
        }
    }

    private fun downloadToMediaStore(url: String, type: String): Boolean {
        val request = Request.Builder().url(url).build()
        okHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            val isVideo = type.equals("video", ignoreCase = true)
            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "linkup-${System.currentTimeMillis()}" }
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    if (isVideo) Environment.DIRECTORY_MOVIES + "/LinkUp" else Environment.DIRECTORY_PICTURES + "/LinkUp")
            }
            val uri = hostActivity.contentResolver.insert(collection, values) ?: return false
            hostActivity.contentResolver.openOutputStream(uri)?.use { out ->
                body.byteStream().copyTo(out)
            } ?: return false
            return true
        }
    }

    /** 选图（系统相册选择器）→ 拷进 cache → 回调路径数组；权限由选择器自身承担（API 30+ 免读权限）。 */
    private fun selectPictures(webView: WebView, intent: H5Commands.Intent.SelectPictures) {
        val picker = hostActivity as? PicturePickingHost ?: return
        picker.pickImages(intent.maxNum) { uris ->
            val paths = uris.mapNotNull { uri -> copyToCache(uri) }
            val array = paths.joinToString(",", "[", "]") { "\"$it\"" }
            webView.evaluateJavascript("${intent.callback}($array)", null)
        }
    }

    private fun copyToCache(uri: Uri): String? = runCatching {
        val dir = File(hostActivity.cacheDir, "h5-media").apply { mkdirs() }
        val file = File(dir, "pick-${System.currentTimeMillis()}-${uri.lastPathSegment?.takeLast(12) ?: "img"}")
        hostActivity.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: return null
        file.absolutePath
    }.getOrNull()

    private fun joinGroup(intent: H5Commands.Intent.JoinQQGroup) {
        val url = "https://qm.qq.com/cgi-bin/qm/qr?k=${Uri.encode(intent.key)}&uin=${Uri.encode(intent.uin)}"
        runCatching {
            hostActivity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(hostActivity, hostActivity.getString(R.string.invite_join_qq_group, intent.uin), Toast.LENGTH_SHORT).show()
        }
    }
}

/** 选图宿主：容器 Activity 实现它，把系统相册选择器接给拦截器。 */
interface PicturePickingHost {
    fun pickImages(maxImages: Int, onResult: (List<Uri>) -> Unit)
}
