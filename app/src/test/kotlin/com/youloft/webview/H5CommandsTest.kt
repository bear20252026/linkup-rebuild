package com.youloft.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `CommonInterceptor.g0` 的 13 action 路由：命令名与参数键逐字对齐 dex 提取的 switch。 */
class H5CommandsTest {

    private fun route(command: String, vararg args: Pair<String, String>) =
        H5Commands.route(command, args.toMap())

    @Test
    fun h5ActivityOpensUrl() {
        assertEquals(
            H5Commands.Intent.OpenH5("https://x/y"),
            route(H5Commands.H5_ACTIVITY, "url" to "https://x/y"),
        )
    }

    @Test
    fun clipboardReadTextCarriesCallback() {
        assertEquals(
            H5Commands.Intent.ReadClipboard("cb"),
            route(H5Commands.CLIPBOARD_READ_TEXT, "callback" to "cb"),
        )
    }

    @Test
    fun permissionTipMapsTypeVocabulary() {
        // 原版 0/1/2/3 → chatAuth 9/24/12/11。
        assertEquals(H5Commands.Intent.PermissionTip(9), route(H5Commands.PERMISSION_TIP, "type" to "0"))
        assertEquals(H5Commands.Intent.PermissionTip(24), route(H5Commands.PERMISSION_TIP, "type" to "1"))
        assertEquals(H5Commands.Intent.PermissionTip(12), route(H5Commands.PERMISSION_TIP, "type" to "2"))
        assertEquals(H5Commands.Intent.PermissionTip(11), route(H5Commands.PERMISSION_TIP, "type" to "3"))
        assertEquals(H5Commands.Intent.Ignored, route(H5Commands.PERMISSION_TIP, "type" to "9"))
        // onUserPermissionTip 直接用 type 原值。
        assertEquals(H5Commands.Intent.PermissionTip(18), route(H5Commands.ON_USER_PERMISSION_TIP, "type" to "18"))
    }

    @Test
    fun sendRecoverMessagePicksCmd18Or19() {
        assertEquals(
            H5Commands.Intent.SendRecoverMessage(10000001, cmdType = 18),
            route(H5Commands.SEND_RECOVER_MESSAGE, "userid" to "10000001", "type" to "1"),
        )
        assertEquals(
            H5Commands.Intent.SendRecoverMessage(10000001, cmdType = 19),
            route(H5Commands.SEND_RECOVER_MESSAGE, "userid" to "10000001", "type" to "0"),
        )
        assertEquals(H5Commands.Intent.Ignored, route(H5Commands.SEND_RECOVER_MESSAGE))
    }

    @Test
    fun showOrHideBtnReadsBooleanSpellings() {
        assertEquals(
            H5Commands.Intent.ToggleBackButton(true),
            route(H5Commands.SHOW_OR_HIDE_BTN, "isShow" to "true"),
        )
        assertEquals(
            H5Commands.Intent.ToggleBackButton(false),
            route(H5Commands.SHOW_OR_HIDE_BTN, "isShow" to "false"),
        )
    }

    @Test
    fun saveMediaSplitsListAndKeepsCallback() {
        val intent = route(
            H5Commands.SAVE_MEDIA_FILE,
            "type" to "video",
            "list" to "https://a/1.mp4, https://a/2.mp4",
            "callback" to "saved",
        ) as H5Commands.Intent.SaveMedia
        assertEquals("video", intent.type)
        assertEquals(listOf("https://a/1.mp4", "https://a/2.mp4"), intent.list)
        assertEquals("saved", intent.callback)
        assertEquals(H5Commands.Intent.Ignored, route(H5Commands.SAVE_MEDIA_FILE, "type" to "image"))
    }

    @Test
    fun selectPicturesDefaultsAreConservative() {
        val intent = route(H5Commands.SELECT_PICTURES, "callback" to "picked") as H5Commands.Intent.SelectPictures
        assertEquals("image", intent.type)
        assertEquals(1, intent.maxNum)
        assertEquals(false, intent.original)
        assertEquals("picked", intent.callback)
    }

    @Test
    fun trackEventAndMemberPopCarryPayload() {
        assertEquals(
            H5Commands.Intent.TrackEvent("tap", true, "{\"a\":1}"),
            route(H5Commands.DATA_TRACK_EVENT, "event" to "tap", "isOnce" to "true", "properties" to "{\"a\":1}"),
        )
        assertEquals(
            H5Commands.Intent.MemberPop(2, "banner"),
            route(H5Commands.ON_SHOW_MEMBER_POP, "source" to "2", "bannerName" to "banner"),
        )
    }

    @Test
    fun joinQQGroupNeedsBothKeyAndUin() {
        assertEquals(
            H5Commands.Intent.JoinQQGroup("k1", "123"),
            route(H5Commands.JOIN_QQ_GROUP, "key" to "k1", "uin" to "123"),
        )
        assertEquals(H5Commands.Intent.Ignored, route(H5Commands.JOIN_QQ_GROUP, "uin" to "123"))
    }

    @Test
    fun downloadPercentIsTheOriginalNoOp() {
        assertEquals(H5Commands.Intent.Ignored, route(H5Commands.DOWNLOAD_PERCENT))
    }

    @Test
    fun unknownCommandFallsToUnknown() {
        assertTrue(H5Commands.route("nope", emptyMap()) is H5Commands.Intent.Unknown)
    }

    @Test
    fun callScriptQuotesStringsAndBaresNumbers() {
        assertEquals("cb('it\\'s')", JsBridge.buildCallScript("cb", listOf("it's")))
        assertEquals("cb(1,true)", JsBridge.buildCallScript("cb", listOf(1, true)))
        assertEquals("cb('')", JsBridge.buildCallScript("cb", listOf<Any?>(null)))
        assertEquals("memberCallback(1)", JsBridge.buildCallScript("memberCallback", listOf(1)))
    }
}
