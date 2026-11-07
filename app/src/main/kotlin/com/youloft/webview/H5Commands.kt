package com.youloft.webview

/**
 * `CommonInterceptor.g0`（1.6.6）的 13 个 action 的**纯路由层**：把 (command, args) 翻成
 * [Intent] 描述，Android 执行在 [CommonInterceptor]。命令名从 dex 字串池逐字提取
 * （jadx 的 switch 恢复失败后走 baksmali 常量），参数键与原版 g0 各分支的取值一致。
 *
 * 原版口径：字符串 switch 14 个 case（h5Activity 重复计入 default、downloadPercent 空实现），
 * 与"13 个 action"的记录对齐。
 */
object H5Commands {

    /** 原版 g0 的 case 常量（逐字）。 */
    const val H5_ACTIVITY = "h5Activity"
    const val CLIPBOARD_READ_TEXT = "clipboardReadText"
    const val ON_USER_PERMISSION_TIP = "onUserPermissionTip"
    const val PERMISSION_TIP = "permissionTip"
    const val REFRESH_VIP = "refreshVip"
    const val DOWNLOAD_PERCENT = "downloadPercent"
    const val JUMP_PAGE = "jumpPage"
    const val SHOW_OR_HIDE_BTN = "showOrHideBtn"
    const val TOAST = "toast"
    const val SAVE_MEDIA_FILE = "saveMediaFile"
    const val SELECT_PICTURES = "selectPictures"
    const val SEND_RECOVER_MESSAGE = "sendRecoverMessage"
    const val DATA_TRACK_EVENT = "dataTrackEvent"
    const val JOIN_QQ_GROUP = "joinQQGroup"
    const val ON_SHOW_MEMBER_POP = "onShowMemberPop"

    /** 原版 permissionTip 的 type→chatAuth 子类型映射（0→9、1→24、2→12、3→11）。 */
    val PERMISSION_TIP_TYPES = mapOf(0 to 9, 1 to 24, 2 to 12, 3 to 11)

    sealed interface Intent {
        /** h5Activity：url → 开新 H5 容器（原版 j0 → CommonWebActivity.w）。 */
        data class OpenH5(val url: String) : Intent

        /** clipboardReadText：callback 名。 */
        data class ReadClipboard(val callback: String) : Intent

        /** onUserPermissionTip / permissionTip：向对方设备发 chatAuth 权限提醒。 */
        data class PermissionTip(val chatAuthType: Int) : Intent

        /** refreshVip。 */
        data object RefreshVip : Intent

        /** jumpPage：scheme 交宿主路由。 */
        data class JumpPage(val scheme: String) : Intent

        /** showOrHideBtn：isShow → 返回钮显隐。 */
        data class ToggleBackButton(val show: Boolean) : Intent

        /** toast。 */
        data class Toast(val message: String) : Intent

        /** saveMediaFile：type（image/video）+ list（url 串）+ callback。 */
        data class SaveMedia(val type: String, val list: List<String>, val callback: String) : Intent

        /** selectPictures：type + maxNum + original + callback。 */
        data class SelectPictures(
            val type: String,
            val maxNum: Int,
            val original: Boolean,
            val callback: String,
        ) : Intent

        /** sendRecoverMessage：userid + type（1→18、否则 19，原版 h.K 的两个子类型）。 */
        data class SendRecoverMessage(val userId: Long, val cmdType: Int) : Intent

        /** dataTrackEvent：event + isOnce + properties(JSON 文本)。 */
        data class TrackEvent(val event: String, val isOnce: Boolean, val properties: String) : Intent

        /** joinQQGroup：key + uin。 */
        data class JoinQQGroup(val key: String, val uin: String) : Intent

        /** onShowMemberPop：source + bannerName。 */
        data class MemberPop(val source: Int, val bannerName: String) : Intent

        /** 原版空实现/参数不全。 */
        data object Ignored : Intent

        /** 未知命令（原版走 default 分支返回 null）。 */
        data object Unknown : Intent
    }

    fun route(command: String, args: Map<String, String>): Intent = when (command) {
        H5_ACTIVITY -> args["url"]?.let { Intent.OpenH5(it) } ?: Intent.Ignored
        CLIPBOARD_READ_TEXT -> args["callback"]?.let { Intent.ReadClipboard(it) } ?: Intent.Ignored
        ON_USER_PERMISSION_TIP ->
            args["type"]?.toIntOrNull()?.let { Intent.PermissionTip(it) } ?: Intent.Ignored
        PERMISSION_TIP -> {
            val type = args["type"]?.toIntOrNull() ?: 0
            PERMISSION_TIP_TYPES[type]?.let { Intent.PermissionTip(it) } ?: Intent.Ignored
        }
        REFRESH_VIP -> Intent.RefreshVip
        DOWNLOAD_PERCENT -> Intent.Ignored
        JUMP_PAGE -> args["scheme"]?.let { Intent.JumpPage(it) } ?: Intent.Ignored
        SHOW_OR_HIDE_BTN -> args["isShow"]?.let { Intent.ToggleBackButton(it == "true" || it == "1") }
            ?: Intent.Ignored
        TOAST -> args["msg"]?.let { Intent.Toast(it) } ?: Intent.Ignored
        SAVE_MEDIA_FILE -> {
            val list = args["list"]
                ?.split(",", ";")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            if (list.isEmpty()) {
                Intent.Ignored
            } else {
                Intent.SaveMedia(
                    type = args["type"] ?: "image",
                    list = list,
                    callback = args["callback"].orEmpty(),
                )
            }
        }
        SELECT_PICTURES -> Intent.SelectPictures(
            type = args["type"] ?: "image",
            maxNum = args["maxNum"]?.toIntOrNull() ?: 1,
            original = args["original"] == "true" || args["original"] == "1",
            callback = args["callback"].orEmpty(),
        )
        SEND_RECOVER_MESSAGE -> {
            val userId = args["userid"]?.toLongOrNull() ?: 0L
            if (userId <= 0) {
                Intent.Ignored
            } else {
                Intent.SendRecoverMessage(userId, if (args["type"]?.toIntOrNull() == 1) 18 else 19)
            }
        }
        DATA_TRACK_EVENT -> Intent.TrackEvent(
            event = args["event"].orEmpty(),
            isOnce = args["isOnce"] == "true" || args["isOnce"] == "1",
            properties = args["properties"].orEmpty(),
        )
        JOIN_QQ_GROUP -> {
            val key = args["key"].orEmpty()
            val uin = args["uin"].orEmpty()
            if (key.isBlank() || uin.isBlank()) Intent.Ignored else Intent.JoinQQGroup(key, uin)
        }
        ON_SHOW_MEMBER_POP -> Intent.MemberPop(
            source = args["source"]?.toIntOrNull() ?: -1,
            bannerName = args["bannerName"].orEmpty(),
        )
        else -> Intent.Unknown
    }
}
