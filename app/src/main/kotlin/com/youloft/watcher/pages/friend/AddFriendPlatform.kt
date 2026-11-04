package com.youloft.watcher.pages.friend

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.view.View
import com.mc.fastkit.utils.KeyboardUtils
import com.youloft.watcher.pages.common.LoadingDialog
import java.net.URLEncoder

/**
 * Everything this screen touches outside its own view tree: clipboard, soft keyboard, the share SDKs
 * and the H5 upload of the rendered QR. Bundled into one seam so a test can drive the screen without
 * a camera, WeChat, or a running IME.
 */
interface AddFriendPlatform {
    fun copy(text: String)

    /**
     * The modal sheet the original showed over this screen while a lookup ran: one cached
     * `LoadingDialog` per activity, non-focusable, playing `assets/loading.svga`.
     */
    fun showLoading()

    fun hideLoading()

    fun hideKeyboard(view: View)

    fun isKeyboardShown(): Boolean

    fun watchKeyboard(listener: KeyboardUtils.Listener)

    /** `F0(userId)` — the address the QR encodes. */
    fun qrShareUrl(userId: Long): String

    /** `G0(userId, avatar, nickname)` — the address the WeChat/QQ invite cards send. */
    fun inviteUrl(userId: Long, avatar: String?, nickname: String?): String

    fun isWechatInstalled(): Boolean

    fun isQqInstalled(): Boolean

    fun shareToWechat(url: String, title: String, content: String)

    /** The original also uploaded the launcher icon first and passed its URL as the QQ thumbnail. */
    fun shareToQq(url: String, title: String, content: String)

    /** The PNG of the rendered QR, pushed so the H5 page can show the same picture. */
    fun uploadQrImage(bytes: ByteArray, fileName: String)
}

/**
 * Production double. WeChat/QQ are installed as *SDK* checks in the original (`IWXAPI.isWXAppInstalled`
 * / `Tencent.isQQInstalled`), which are plain package queries — so the query is reproduced, while the
 * share calls themselves still need the SDKs, which are their own cards.
 */
class RealAddFriendPlatform(
    private val activity: Activity,
) : AddFriendPlatform {

    override fun copy(text: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("match_code", text))
    }

    // The recovered base class caches one sheet per activity and reuses it; the 0L floor is exactly
    // what `BaseFrameActivity.showLoading()` passes there.
    private var loading: LoadingDialog? = null

    override fun showLoading() {
        if (activity.isFinishing || activity.isDestroyed) return
        val sheet = loading ?: LoadingDialog(activity, minShownMillis = 0L).also { loading = it }
        sheet.show()
    }

    override fun hideLoading() {
        loading?.dismissAfterMinimum()
    }

    override fun hideKeyboard(view: View) = KeyboardUtils.hideKeyboard(view)

    override fun isKeyboardShown(): Boolean = KeyboardUtils.isKeyboardShown(activity)

    override fun watchKeyboard(listener: KeyboardUtils.Listener) = KeyboardUtils.register(activity, listener)

    override fun qrShareUrl(userId: Long): String = shareUrl(userId, avatar = null, nickname = null, appVersion = "1.6.6")

    override fun inviteUrl(userId: Long, avatar: String?, nickname: String?): String =
        shareUrl(userId, avatar, nickname, appVersion = null)

    override fun isWechatInstalled(): Boolean = isPackageInstalled(WECHAT_PACKAGE)

    override fun isQqInstalled(): Boolean = isPackageInstalled(QQ_PACKAGE)

    override fun shareToWechat(url: String, title: String, content: String) = Unit

    override fun shareToQq(url: String, title: String, content: String) = Unit

    override fun uploadQrImage(bytes: ByteArray, fileName: String) = Unit

    /**
     * The recovered builder is deliberately awkward: it builds the path, then re-opens the *result*
     * to append an encoded fragment. Kept in that order because the output — `…/guard-report#/addMe?…`
     * — is what the H5 page parses.
     */
    private fun shareUrl(userId: Long, avatar: String?, nickname: String?, appVersion: String?): String {
        val base = Uri.parse(H5_BASE)
            .buildUpon().appendPath("business").appendPath("guard-report").build()
            .buildUpon()
        val fragment = if (avatar == null || nickname == null) {
            "/addMe?userid=${encode(userId.toString())}&av=$appVersion"
        } else {
            "/addMe?userid=${encode(userId.toString())}&avatar=${encode(avatar)}&nickname=${encode(nickname)}"
        }
        return base.encodedFragment(fragment).build().toString()
    }

    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        activity.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val H5_BASE = "https://web-link.tttick.com/"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val QQ_PACKAGE = "com.tencent.mobileqq"
    }
}
