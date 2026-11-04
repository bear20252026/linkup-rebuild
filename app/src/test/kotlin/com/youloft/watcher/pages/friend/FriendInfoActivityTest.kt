package com.youloft.watcher.pages.friend

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.youloft.linkup.api.dto.FriendInfo
import com.youloft.linkup.api.dto.Permission
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.R
import com.youloft.watcher.data.FriendEvents
import com.youloft.watcher.pages.friend.FriendFlowFixtures.Recorder
import com.youloft.watcher.pages.friend.FriendFlowFixtures.TestApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
 * Regression net for the rebuilt friend-info screen: every rendered field, the "not friends"
 * fallback, the four action round-trips and the push overlay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class FriendInfoActivityTest {

    private val repository get() = FriendFlowFixtures.repository

    /** Records which dialog opened and lets the test deliver the user's choice. */
    class FakeDialogs : FriendInfoDialogs {
        val opened = mutableListOf<String>()
        var confirmDeleteChoice = true
        var relationChoice: Int? = null
        var remarkInput: String? = null

        override fun confirmDelete(message: String, onConfirm: () -> Unit) {
            opened += "confirmDelete:$message"
            if (confirmDeleteChoice) onConfirm()
        }

        override fun updateRelation(current: Int, onPicked: (Int) -> Unit) {
            opened += "updateRelation:$current"
            relationChoice?.let(onPicked)
        }

        override fun updateRemark(title: String, current: String, hint: String, onSubmit: (String) -> Unit) {
            opened += "updateRemark:$current"
            remarkInput?.let(onSubmit)
        }

        override fun permissionSettings(current: Permission, onResult: (Permission?) -> Unit) {
            opened += "permissionSettings"
        }
    }

    @Before
    fun setUp() {
        FriendFlowFixtures.reset()
    }

    private fun launch(
        userId: Long = 77L,
        source: Int = 2,
        nickName: String? = "列表里的名字",
    ): FriendInfoActivity {
        val intent = Intent(
            ApplicationProvider.getApplicationContext<Context>(),
            FriendInfoActivity::class.java,
        )
            .putExtra(FriendInfoActivity.EXTRA_ID, userId)
            .putExtra(FriendInfoActivity.EXTRA_SOURCE, source)
            .putExtra(FriendInfoActivity.EXTRA_NICK_NAME, nickName)
        val activity = Robolectric.buildActivity(FriendInfoActivity::class.java, intent).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return activity
    }

    private fun info(
        sex: String? = "1",
        relation: String? = "1",
        remark: String? = "",
        createAt: Long = 1_700_000_000L,
        permission: Permission? = null,
        nickname: String? = "本名",
        userId: Long = 77L,
    ) = FriendInfo(
        userId = userId,
        nickname = nickname,
        sex = sex,
        relation = relation,
        remark = remark,
        createAt = createAt,
        permission = permission,
    )

    private fun succeed(data: FriendInfo?) {
        repository.friendInfoResponse = ApiResponse(200, "success", data = data)
    }

    @Test
    fun `every recovered field is rendered from the response`() {
        succeed(
            info(
                sex = "2",
                relation = "3",
                remark = "备注名",
                createAt = 1_700_000_000L,
                permission = Permission(sensLocation = true, sensTrack = true, sensCall = true, sensAppUse = true),
            ),
        )
        val activity = launch()
        val decor = activity.window.decorView

        assertEquals("本名", decor.findViewById<TextView>(R.id.tv_username).text.toString())
        assertEquals("备注名", decor.findViewById<TextView>(R.id.tv_remark).text.toString())
        assertEquals("女", decor.findViewById<TextView>(R.id.tv_sex).text.toString())
        assertTrue(decor.findViewById<TextView>(R.id.tv_sex).compoundDrawablesRelative[2] != null)
        assertEquals("亲人", decor.findViewById<TextView>(R.id.tv_relation).text.toString())
        assertEquals(listOf(77L), repository.friendInfoRequests)
        // yyyy-MM-dd of 1700000000000 ms, formatted with the device locale like the original.
        assertTrue(
            decor.findViewById<TextView>(R.id.tv_add_date).text.toString().matches(Regex("""\d{4}-\d{2}-\d{2}""")),
        )
        assertEquals("匹配码：77", decor.findViewById<TextView>(R.id.tv_match_code).text.toString())
    }

    @Test
    fun `permission summary counts the nine switches and falls back when none are open`() {
        succeed(info(permission = Permission(sensLocation = true, sensTrack = true, sensPhoneUse = true)))
        val activity = launch()
        val permissions = activity.window.decorView.findViewById<TextView>(R.id.tv_permissions)

        assertEquals(
            activity.getString(R.string.how_many_data, 3),
            permissions.text.toString(),
        )

        succeed(info(permission = Permission()))
        val none = launch()
        assertEquals(
            none.getString(R.string.no_data_is_visible),
            none.window.decorView.findViewById<TextView>(R.id.tv_permissions).text.toString(),
        )

        succeed(info(permission = null))
        val missing = launch()
        assertEquals(
            missing.getString(R.string.no_data_is_visible),
            missing.window.decorView.findViewById<TextView>(R.id.tv_permissions).text.toString(),
        )
    }

    @Test
    fun `a failed load falls back to the not-friends presentation`() {
        repository.friendInfoResponse = ApiResponse(500, "gone", data = null)
        val activity = launch(nickName = "上次看到的名字")
        val decor = activity.window.decorView

        assertEquals(View.VISIBLE, decor.findViewById<View>(R.id.tv_not_friend).visibility)
        assertEquals("上次看到的名字", decor.findViewById<TextView>(R.id.tv_username).text.toString())
        assertEquals("匹配码：77", decor.findViewById<TextView>(R.id.tv_match_code).text.toString())
        listOf(R.id.rl_remark, R.id.rl_relation, R.id.ll_permissions, R.id.ll_add_date, R.id.iv_delete, R.id.toolbar)
            .forEach { id ->
                assertEquals(View.GONE, decor.findViewById<View>(id).visibility, "view $id should hide")
            }
    }

    @Test
    fun `a successful load with no data also falls back`() {
        succeed(null)
        val activity = launch()

        assertEquals(View.VISIBLE, activity.window.decorView.findViewById<View>(R.id.tv_not_friend).visibility)
    }

    @Test
    fun `an empty remark in the response does not overwrite the label`() {
        succeed(info(remark = ""))
        val activity = launch()

        assertEquals("", activity.window.decorView.findViewById<TextView>(R.id.tv_remark).text.toString())
    }

    @Test
    fun `unknown sex and relation degrade to the other branch instead of crashing`() {
        succeed(info(sex = null, relation = null))
        val activity = launch()
        val decor = activity.window.decorView

        assertEquals("", decor.findViewById<TextView>(R.id.tv_sex).text.toString())
        assertEquals("其他", decor.findViewById<TextView>(R.id.tv_relation).text.toString())
        assertTrue(decor.findViewById<TextView>(R.id.tv_sex).compoundDrawablesRelative[2] == null)
    }

    @Test
    fun `delete goes through the confirm dialog then toasts, broadcasts and finishes`() {
        succeed(info())
        val activity = launch()
        val dialogs = FakeDialogs()
        activity.dialogs = dialogs
        val events = mutableListOf<Long>()
        CoroutineScope(Dispatchers.Unconfined).launch { FriendEvents.friendDeleted.collect { events += it } }

        activity.window.decorView.findViewById<View>(R.id.iv_delete).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(
            listOf("confirmDelete:" + activity.getString(R.string.are_you_sure_to_delete_this_friend)),
            dialogs.opened,
        )
        assertEquals(listOf(77L), repository.deletions)
        assertEquals(
            activity.getString(R.string.delete_success),
            ShadowToast.getTextOfLatestToast()?.toString(),
        )
        assertEquals(listOf(77L), events)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `cancelling the delete dialog sends nothing`() {
        succeed(info())
        val activity = launch()
        val dialogs = FakeDialogs().apply { confirmDeleteChoice = false }
        activity.dialogs = dialogs

        activity.window.decorView.findViewById<View>(R.id.iv_delete).performClick()

        assertEquals(1, dialogs.opened.size)
        assertTrue(repository.deletions.isEmpty())
        assertTrue(!activity.isFinishing)
    }

    @Test
    fun `a failed delete only toasts the server message`() {
        succeed(info())
        repository.deleteResponse = ApiResponse(500, "服务器拒绝", data = null)
        val activity = launch()
        activity.dialogs = FakeDialogs()

        activity.window.decorView.findViewById<View>(R.id.iv_delete).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals("服务器拒绝", ShadowToast.getTextOfLatestToast()?.toString())
        assertTrue(!activity.isFinishing)
    }

    @Test
    fun `relation row is inert until the profile tells us the relation`() {
        repository.friendInfoResponse = ApiResponse(500, "gone", data = null)
        val activity = launch()
        val dialogs = FakeDialogs()
        activity.dialogs = dialogs

        activity.window.decorView.findViewById<View>(R.id.rl_relation).performClick()

        assertTrue(dialogs.opened.isEmpty())
    }

    @Test
    fun `relation update repaints the row and toasts`() {
        succeed(info(relation = "1"))
        val activity = launch()
        val dialogs = FakeDialogs().apply { relationChoice = 3 }
        activity.dialogs = dialogs

        activity.window.decorView.findViewById<View>(R.id.rl_relation).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf("updateRelation:1"), dialogs.opened)
        assertEquals(listOf(77L to 3), repository.relationUpdates)
        assertEquals("亲人", activity.window.decorView.findViewById<TextView>(R.id.tv_relation).text.toString())
        assertEquals(
            activity.getString(R.string.update_success),
            ShadowToast.getTextOfLatestToast()?.toString(),
        )
    }

    @Test
    fun `remark update writes the new remark back`() {
        succeed(info(remark = "旧备注"))
        val activity = launch()
        val dialogs = FakeDialogs().apply { remarkInput = "新备注" }
        activity.dialogs = dialogs

        activity.window.decorView.findViewById<View>(R.id.rl_remark).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf("updateRemark:旧备注"), dialogs.opened)
        assertEquals(listOf(77L to "新备注"), repository.remarkUpdates)
        assertEquals("新备注", activity.window.decorView.findViewById<TextView>(R.id.tv_remark).text.toString())
    }

    @Test
    fun `the permissions row needs a permission object before it opens anything`() {
        succeed(info(permission = null))
        val activity = launch()
        val dialogs = FakeDialogs()
        activity.dialogs = dialogs

        activity.window.decorView.findViewById<View>(R.id.ll_permissions).performClick()
        assertTrue(dialogs.opened.isEmpty())

        succeed(info(permission = Permission(sensTrack = true)))
        val withPermission = launch()
        withPermission.dialogs = dialogs
        withPermission.window.decorView.findViewById<View>(R.id.ll_permissions).performClick()
        assertEquals(listOf("permissionSettings"), dialogs.opened)
    }

    @Test
    fun `chat entry routes through the navigation seam`() {
        succeed(info())
        val activity = launch()
        val recorder = Recorder()
        activity.navigation = recorder

        activity.window.decorView.findViewById<View>(R.id.iv_chat).performClick()

        assertEquals(listOf("chat:77"), recorder.events)
    }

    @Test
    fun `a delete push arms the overlay with its own copy and a logout push with another`() {
        succeed(info())
        val before = launch()
        assertEquals(View.GONE, before.window.decorView.findViewById<View>(R.id.view_delete_or_logout).visibility)

        FriendFlowFixtures.source.publishCommand(com.youloft.watcher.bean.CmdFriendBean(type = 2))
        val armed = launch()
        assertEquals(View.VISIBLE, armed.window.decorView.findViewById<View>(R.id.view_delete_or_logout).visibility)
        armed.window.decorView.findViewById<View>(R.id.view_delete_or_logout).performClick()
        assertEquals("你们已不是好友", ShadowToast.getTextOfLatestToast()?.toString())

        FriendFlowFixtures.source.publishCommand(com.youloft.watcher.bean.CmdFriendBean(type = 4))
        val logout = launch()
        logout.window.decorView.findViewById<View>(R.id.view_delete_or_logout).performClick()
        assertEquals("该账号已注销", ShadowToast.getTextOfLatestToast()?.toString())
    }

    @Test
    fun `the production dialog wiring really puts up a confirm before deleting`() {
        succeed(info())
        repository.deleteResponse = ApiResponse(200, "success")
        // No FakeDialogs assigned: this is the default RealFriendInfoDialogs path.
        val activity = launch()

        activity.window.decorView.findViewById<View>(R.id.iv_delete).performClick()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        assertEquals(
            activity.getString(R.string.are_you_sure_to_delete_this_friend),
            dialog.findViewById<android.widget.TextView>(R.id.tv_title).text.toString(),
        )

        dialog.findViewById<android.widget.TextView>(R.id.tv_confirm).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf(77L), repository.deletions)
        assertTrue(!dialog.isShowing)
    }

    @Test
    fun `the permission dialog the screen really opens hides its close button`() {
        succeed(info(permission = Permission(sensLocation = true, sensTrack = true)))
        // Default dialogs: this is the production wiring, where the recovered call site passed false.
        val activity = launch()

        activity.window.decorView.findViewById<View>(R.id.ll_permissions).performClick()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertEquals(
            View.GONE,
            dialog.findViewById<View>(R.id.iv_close).visibility,
            "the recovered friend-info entry hid the ✕, leaving outside-tap as the only exit",
        )
        assertTrue(
            dialog.findViewById<android.widget.CheckBox>(R.id.cb_sens_location).isChecked,
        )

        dialog.findViewById<TextView>(R.id.tv_confirm).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `analytics labels map the recovered entry sources`() {
        val activity = launch()

        assertEquals("守护Tab-头像功能弹窗", activity.analyticsLabel(1))
        assertEquals("消息中心-我的好友", activity.analyticsLabel(2))
        assertEquals("im头像", activity.analyticsLabel(3))
        assertEquals("", activity.analyticsLabel(0))
    }

    @Test
    fun `back finishes`() {
        succeed(info())
        val activity = launch()

        activity.window.decorView.findViewById<View>(R.id.iv_back).performClick()

        assertTrue(activity.isFinishing)
    }
}
