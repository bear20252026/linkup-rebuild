package com.youloft.watcher.pages.friend

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mc.fastkit.utils.KeyboardUtils
import com.youloft.linkup.api.dto.Friend
import com.youloft.linkup.api.dto.FriendApply
import com.youloft.linkup.api.dto.FriendInfo
import com.youloft.linkup.api.dto.Permission
import com.youloft.linkup.api.dto.SearchFriend
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.R
import com.youloft.watcher.pages.friend.FriendFlowFixtures.Recorder
import com.youloft.watcher.pages.friend.FriendFlowFixtures.TestApplication
import com.youloft.watcher.view.QrCodeRenderer
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Regression net for the rebuilt add-friend screen: the four ways in, the decision tree that turns one
 * typed string into either a match-code lookup or a sealed phone upload, the verify sheet's routing,
 * and what happens once the apply request comes back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class AddFriendActivityTest {

    private val repository get() = FriendFlowFixtures.repository
    private val flags get() = FriendFlowFixtures.flags
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /** Records the outside world this screen touches and lets the test answer it. */
    class FakePlatform : AddFriendPlatform {
        val calls = mutableListOf<String>()
        var keyboardShown = false
        var wechatInstalled = false
        var qqInstalled = false

        override fun copy(text: String) { calls += "copy:$text" }
        override fun showLoading() { calls += "showLoading" }
        override fun hideLoading() { calls += "hideLoading" }
        override fun hideKeyboard(view: View) { calls += "hideKeyboard" }
        override fun isKeyboardShown(): Boolean = keyboardShown
        override fun watchKeyboard(listener: KeyboardUtils.Listener) { calls += "watchKeyboard" }
        override fun qrShareUrl(userId: Long): String = "https://web-link.tttick.com/business/guard-report#/addMe?userid=$userId&av=1.6.6"
        override fun inviteUrl(userId: Long, avatar: String?, nickname: String?): String = "invite:$userId"
        override fun isWechatInstalled(): Boolean = wechatInstalled
        override fun isQqInstalled(): Boolean = qqInstalled
        override fun shareToWechat(url: String, title: String, content: String) { calls += "wechat:$url" }
        override fun shareToQq(url: String, title: String, content: String) { calls += "qq:$url" }
        override fun uploadQrImage(bytes: ByteArray, fileName: String) { calls += "uploadQr:$fileName:${bytes.size}" }
    }

    class FakeDialogs : AddFriendDialogs {
        val opened = mutableListOf<String>()

        /** Set by the last verify sheet, so the test decides when and with what the user confirms. */
        var pendingConfirm: ((Int) -> Unit)? = null
        var permissionChoice: Permission? = null

        override fun verifyInfo(
            friend: Friend,
            type: Int,
            mySex: Int,
            onVipLabelClick: (source: Int, label: String) -> Unit,
            onConfirm: (Int, () -> Unit) -> Unit,
        ) {
            opened += "verify:${friend.userId}:$type:mySex=$mySex"
            pendingConfirm = { relation -> onConfirm(relation) {} }
        }

        override fun permissionSettings(
            source: Int,
            onResult: (Permission?) -> Unit,
            onDismissed: () -> Unit,
        ) {
            opened += "permissions:$source"
            onResult(permissionChoice)
            onDismissed()
        }
    }

    private lateinit var platform: FakePlatform
    private lateinit var dialogs: FakeDialogs
    private lateinit var recorder: Recorder

    @Before
    fun setUp() {
        FriendFlowFixtures.reset()
        FriendFlowFixtures.seedAccount(userId = 1234567890L, nickname = "我的名字", sex = 1)
        platform = FakePlatform()
        dialogs = FakeDialogs()
        recorder = Recorder()
    }

    private fun launch(source: Int = 0, matchCodeFromH5: Long? = null): AddFriendActivity {
        val intent = Intent(context, AddFriendActivity::class.java)
            .putExtra(AddFriendActivity.EXTRA_SOURCE, source)
        if (matchCodeFromH5 != null) intent.putExtra(AddFriendActivity.EXTRA_MATCH_CODE_FROM_H5, matchCodeFromH5)
        return launch(intent)
    }

    private fun launch(intent: Intent): AddFriendActivity {
        val controller = Robolectric.buildActivity(AddFriendActivity::class.java, intent)
        controller.get().platform = platform
        controller.get().dialogs = dialogs
        controller.get().navigation = recorder
        controller.setup()
        idle()
        return controller.get()
    }

    /** The H5 path is the shortest way to a verify sheet, so most apply-flow tests use it. */
    private fun launchToVerify(
        relation: Int? = null,
        found: Friend = Friend(userId = 555L, nickname = "网友"),
    ): AddFriendActivity {
        repository.preConfirmResponse = ApiResponse(200, "success", data = found)
        val activity = launch(source = AddFriendActivity.SOURCE_H5, matchCodeFromH5 = 555L)
        if (relation != null) {
            dialogs.pendingConfirm?.invoke(relation)
            idle()
        }
        return activity
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun <T : View> view(activity: AddFriendActivity, id: Int): T = activity.window.decorView.findViewById(id)

    private fun toast(): String? = ShadowToast.getTextOfLatestToast()?.toString()

    /** A key the recovered client can strip and parse, so the sealed branch is reachable in tests. */
    private fun serverPem(): String {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val body = Base64.getEncoder().withoutPadding().encodeToString(pair.public.encoded).chunked(64).joinToString("\n")
        return "-----BEGIN PUBLIC KEY-----\n$body\n----END PUBLIC KEY-----\n"
    }

    @Test
    fun `the match code is displayed in four-digit groups`() {
        val activity = launch()

        assertEquals("1234 5678 90", view<TextView>(activity, R.id.tv_match_code).text.toString())
    }

    @Test
    fun `copying sends the bare id, not the spaced display`() {
        val activity = launch()

        view<TextView>(activity, R.id.tv_match_code).performClick()
        idle()

        assertEquals(listOf("copy:1234567890"), platform.calls.filter { it.startsWith("copy:") })
        assertEquals(context.getString(R.string.copy_success), toast())
    }

    @Test
    fun `the h5 entry looks the code up and marks the sheet as coming from h5`() {
        val activity = launchToVerify()

        assertEquals(listOf(555L), repository.preConfirmRequests)
        assertEquals(listOf("verify:555:5:mySex=1"), dialogs.opened)
        assertEquals("showLoading", platform.calls.first())
    }

    @Test
    fun `an h5 entry without a usable code only complains`() {
        launch(source = AddFriendActivity.SOURCE_H5, matchCodeFromH5 = 0L)

        assertEquals(context.getString(R.string.please_enter_the_correct_matching_code), toast())
        assertTrue(repository.preConfirmRequests.isEmpty())
    }

    @Test
    fun `only store builds with a promise to make show a hint under the cards`() {
        flags.channel = "3801"
        var activity = launch()
        assertEquals(View.VISIBLE, view<TextView>(activity, R.id.tv_tips).visibility)
        assertEquals("定位轨迹等共享功能均需双方相互授权同意才可使用", view<TextView>(activity, R.id.tv_tips).text.toString())

        flags.channel = "3301"
        activity = launch()
        assertEquals(context.getString(R.string.add_friend_bottom_tip), view<TextView>(activity, R.id.tv_tips).text.toString())

        flags.channel = "3300"
        activity = launch()
        assertEquals("需用户双方安装APP并授权才可实现定位功能", view<TextView>(activity, R.id.tv_tips).text.toString())

        flags.channel = "0"
        activity = launch()
        assertEquals(View.GONE, view<TextView>(activity, R.id.tv_tips).visibility)
    }

    @Test
    fun `every entry point reports where it came from`() {
        assertEquals("好友申请", AddFriendActivity.analyticsLabel(8))
        assertEquals("我的好友", AddFriendActivity.analyticsLabel(6))
        assertEquals("H5", AddFriendActivity.analyticsLabel(7))
        assertEquals("实时截屏", AddFriendActivity.analyticsLabel(20), "1.6.6 added 20 and 21")
        assertEquals("隐私设置", AddFriendActivity.analyticsLabel(21))
        assertEquals("其他", AddFriendActivity.analyticsLabel(0))
    }

    @Test
    fun `the search row appears only while the box has text`() {
        val activity = launch()
        assertEquals(View.GONE, view<View>(activity, R.id.iv_search_clear).visibility)

        view<EditText>(activity, R.id.tvSearchFriend).setText("138")
        assertEquals(View.VISIBLE, view<View>(activity, R.id.iv_search_clear).visibility)
        assertEquals(View.VISIBLE, view<View>(activity, R.id.tv_search_action).visibility)

        view<EditText>(activity, R.id.tvSearchFriend).setText("")
        assertEquals(View.GONE, view<View>(activity, R.id.iv_search_clear).visibility)
    }

    @Test
    fun `an eight digit string is a match code and goes straight to the lookup`() {
        repository.publicKey = serverPem()
        repository.preConfirmResponse = ApiResponse(200, "success", data = Friend(userId = 88L))
        val activity = launch()
        view<EditText>(activity, R.id.tvSearchFriend).setText("12345678")

        activity.searchNow()
        idle()

        assertEquals(listOf(12345678L), repository.preConfirmRequests)
        assertTrue(repository.contactSearches.isEmpty())
        assertEquals("搜索匹配码", activity.addTypeLabel())
        assertEquals(listOf("verify:88:2:mySex=1"), dialogs.opened)
    }

    @Test
    fun `a phone number is sealed and uploaded instead of looked up`() {
        repository.publicKey = serverPem()
        repository.contactResponse = ApiResponse(
            200,
            "success",
            data = SearchFriend(
                isFriendLimit = true,
                list = listOf(FriendInfo(userId = 99L, nickname = "通讯录里的人", remark = "")),
            ),
        )
        val activity = launch()
        view<EditText>(activity, R.id.tvSearchFriend).setText("13800138000")

        activity.searchNow()
        idle()

        val sealed = repository.contactSearches.single()
        assertTrue(sealed.first.isNotEmpty() && sealed.second.isNotEmpty(), "the body is the sealed envelope")
        assertEquals("搜索手机号", activity.addTypeLabel())
        assertEquals(listOf("verify:99:3:mySex=1"), dialogs.opened)
    }

    @Test
    fun `a found person takes the quota flag from the search and the name from the remark`() {
        val info = FriendInfo(userId = 99L, nickname = "甲", remark = "乙")

        val friend = AddFriendActivity.foundFrom(info, searchFriendLimit = true)

        assertTrue(friend.isFriendLimit)
        assertEquals("乙", friend.nickname)
        assertEquals(99L, friend.userId)
    }

    @Test
    fun `without a server key the screen neither toasts nor leaves the stale text in the box`() {
        repository.publicKey = null
        val activity = launch()
        view<EditText>(activity, R.id.tvSearchFriend).setText("12345678")

        activity.searchNow()
        idle()

        assertEquals(0, view<EditText>(activity, R.id.tvSearchFriend).text?.length)
        assertNull(toast())
        assertTrue(repository.preConfirmRequests.isEmpty())
    }

    @Test
    fun `a lookup that really fails says the code is invalid`() {
        repository.publicKey = serverPem()
        repository.preConfirmResponse = ApiResponse(404, "not found")
        val activity = launch()
        view<EditText>(activity, R.id.tvSearchFriend).setText("12345678")

        activity.searchNow()
        idle()

        assertEquals(context.getString(R.string.invalid_matching_code), toast())
        assertEquals(0, view<EditText>(activity, R.id.tvSearchFriend).text?.length)
    }

    @Test
    fun `an empty search does nothing at all`() {
        repository.publicKey = serverPem()
        val activity = launch()

        activity.searchNow()
        idle()

        assertTrue(repository.preConfirmRequests.isEmpty() && repository.contactSearches.isEmpty())
    }

    @Test
    fun `the ime search key is answered, every other key is not`() {
        repository.publicKey = serverPem()
        val activity = launch()

        assertFalse(activity.onEditorAction(EditorInfo.IME_ACTION_DONE))
        assertTrue(activity.onEditorAction(EditorInfo.IME_ACTION_SEARCH))
        assertEquals(context.getString(R.string.please_enter_the_correct_matching_code), toast())

        view<EditText>(activity, R.id.tvSearchFriend).setText("12345678")
        assertTrue(activity.onEditorAction(EditorInfo.IME_ACTION_SEARCH))
        idle()
        assertEquals(listOf(12345678L), repository.preConfirmRequests)
    }

    @Test
    fun `the mask only covers the screen while the box is focused and the keyboard is up`() {
        val activity = launch()
        assertEquals(View.GONE, view<View>(activity, R.id.view_input_mask).visibility)

        activity.onFocusChanged(true)
        assertEquals(View.GONE, view<View>(activity, R.id.view_input_mask).visibility)

        platform.keyboardShown = true
        activity.onFocusChanged(true)
        assertEquals(View.VISIBLE, view<View>(activity, R.id.view_input_mask).visibility)

        activity.onKeyboardChanged(false)
        assertEquals(View.GONE, view<View>(activity, R.id.view_input_mask).visibility)

        view<View>(activity, R.id.view_input_mask).performClick()
        idle()
        assertTrue(platform.calls.contains("hideKeyboard"))
    }

    @Test
    fun `a scanned eight character code is a match code and a url carries a userid`() {
        repository.preConfirmResponse = ApiResponse(200, "success", data = Friend(userId = 777L))
        val activity = launch()

        activity.onQrResult("87654321")
        idle()

        assertEquals(listOf(87654321L), repository.preConfirmRequests)
        assertEquals(listOf("verify:777:1:mySex=1"), dialogs.opened, "a scan reports type 1")

        repository.preConfirmRequests.clear()
        dialogs.opened.clear()
        activity.onQrResult("https://link.tttick.com/x#/addMe?userid=4242&from=qr")
        idle()

        assertEquals(listOf(4242L), repository.preConfirmRequests)
    }

    @Test
    fun `a scan that is neither answers with the invalid code toast`() {
        val activity = launch()

        activity.onQrResult("hello-world")
        idle()

        assertEquals(context.getString(R.string.invalid_qr_code), toast())
        assertTrue(repository.preConfirmRequests.isEmpty())
    }

    @Test
    fun `confirming a relation applies with no sens settings attached`() {
        repository.applyResponse = ApiResponse(200, "success", data = FriendApply(applyStatus = 0, friendId = 555L))
        launchToVerify(relation = 3)

        assertEquals(listOf(Triple(555L, 3, null as Permission?)), repository.applications)
    }

    @Test
    fun `a stranger at their own limit is never sent a request`() {
        launchToVerify(relation = 2, found = Friend(userId = 555L, otherFriendLimit = true))

        assertTrue(repository.applications.isEmpty())
        assertEquals(context.getString(R.string.ta_friend_has_reached_the_limit), toast())
    }

    @Test
    fun `a fresh application says it was sent once the consent sheet is gone`() {
        flags.askPermissions = true
        repository.applyResponse = ApiResponse(200, "success", data = FriendApply(applyStatus = 0, friendId = 9L))

        launchToVerify(relation = 2)

        assertEquals(listOf("permissions:6"), dialogs.opened.filter { it.startsWith("permissions:") }, "the sheet remembers H5")
        assertEquals(context.getString(R.string.friend_application_sent_successfully), toast())
    }

    @Test
    fun `without the consent gate the success toast still fires`() {
        flags.askPermissions = false
        repository.applyResponse = ApiResponse(200, "success", data = FriendApply(applyStatus = 0, friendId = 9L))

        launchToVerify(relation = 2)

        assertTrue(dialogs.opened.none { it.startsWith("permissions:") })
        assertEquals(context.getString(R.string.friend_application_sent_successfully), toast())
    }

    @Test
    fun `the chosen consent set is handed to the privacy updater`() {
        flags.askPermissions = true
        dialogs.permissionChoice = Permission(sensLocation = true)
        repository.applyResponse = ApiResponse(200, "success", data = FriendApply(applyStatus = 0, friendId = 9L))
        repository.preConfirmResponse = ApiResponse(200, "success", data = Friend(userId = 555L))
        val updates = mutableListOf<Pair<Permission, Long>>()

        // Stopping short of the confirm keeps the sheet open, so the updater can be installed first.
        val activity = launchToVerify()
        activity.permissionUpdater = { permission, friendId -> updates += permission to friendId }
        dialogs.pendingConfirm?.invoke(2)
        idle()

        assertEquals(1, updates.size)
        assertEquals(9L, updates.single().second)
        assertEquals(true, updates.single().first.sensLocation)
    }

    @Test
    fun `already being friends is reported as such and never re-announced`() {
        repository.applyResponse = ApiResponse(200, "success", data = FriendApply(applyStatus = 1, friendId = 9L))
        val activity = launchToVerify(relation = 2)

        assertEquals(context.getString(R.string.added_friend), toast())
        assertTrue(activity.addFriendEvent(1).contains("add_result" to "已是好友"))
    }

    @Test
    fun `a rejected request shows the server message`() {
        repository.applyResponse = ApiResponse(500, "对方已关闭添加")

        launchToVerify(relation = 2)

        assertEquals("对方已关闭添加", toast())
    }

    @Test
    fun `the add_friend event pairs the entry type with the picked relation`() {
        repository.applyResponse = ApiResponse(200, "success", data = FriendApply(applyStatus = 0, friendId = 9L))
        val activity = launchToVerify(relation = 2)

        assertEquals(
            listOf("add_type" to "扫一扫", "relationship" to "挚友", "add_result" to "申请成功"),
            activity.addFriendEvent(0),
        )
    }

    @Test
    fun `without wechat the invite row only points at the store`() {
        val activity = launch()

        view<View>(activity, R.id.scl_wechat).performClick()
        idle()

        assertEquals(context.getString(R.string.please_install_wx_first), toast())
        assertTrue(platform.calls.none { it.startsWith("wechat:") })
    }

    @Test
    fun `with wechat installed the invite row shares the profile link`() {
        platform.wechatInstalled = true
        val activity = launch()

        view<View>(activity, R.id.scl_wechat).performClick()
        idle()

        assertTrue(platform.calls.contains("wechat:invite:1234567890"))
    }

    @Test
    fun `without qq the invite row only points at the store`() {
        val activity = launch()

        view<View>(activity, R.id.scl_qq).performClick()
        idle()

        assertEquals(context.getString(R.string.please_install_qq_first), toast())
        assertTrue(platform.calls.none { it.startsWith("qq:") })
    }

    @Test
    fun `with qq installed the invite row shares too`() {
        platform.qqInstalled = true
        val activity = launch()

        view<View>(activity, R.id.scl_qq).performClick()
        idle()

        assertTrue(platform.calls.contains("qq:invite:1234567890"))
    }

    @Test
    fun `the qr card is drawn on entry and its png is handed to the h5 upload`() {
        FriendFlowFixtures.qr = QrCodeRenderer { _, _ -> Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888) }

        val activity = launch()
        idle()

        assertTrue(platform.calls.any { it.startsWith("uploadQr:match_qr_code.png:") })
        assertNotNull(view<ImageView>(activity, R.id.iv_code).drawable)
    }

    @Test
    fun `the app graph hands out the recovered painter`() {
        // The test fixture deliberately draws nothing; production must not.
        val painter = com.youloft.watcher.view.MatchCodeQrPainter()

        assertNotNull(painter.render("https://web-link.tttick.com/business/guard-report#/addMe?userid=1&av=1.6.6", 115))
    }

    @Test
    fun `a screen that cannot draw the qr leaves the card empty`() {
        val activity = launch()
        idle()

        assertNull(view<ImageView>(activity, R.id.iv_code).drawable)
        assertTrue(platform.calls.none { it.startsWith("uploadQr:") })
    }

    @Test
    fun `the scanner is launched through a result launcher, as in the original`() {
        val activity = launch()

        activity.launchScanner()
        idle()

        val started = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
        assertEquals(QRCodeScanActivity::class.java.name, started.component?.className)
    }

    @Test
    fun `the contacts entry launches the real screen now`() {
        val activity = launch()

        DefaultFriendFlowNavigation(activity).openContactList()
        idle()

        val started = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
        assertEquals(ContactInfoListActivity::class.java.name, started.component?.className)
    }

    @Test
    fun `the close button leaves the screen`() {
        val activity = launch()

        view<View>(activity, R.id.iv_close).performClick()
        idle()

        assertTrue(activity.isFinishing)
    }
}
