package com.youloft.watcher.pages.friend

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.youloft.linkup.api.dto.Permission
import com.youloft.watcher.R
import com.youloft.watcher.pages.common.ConfirmDialog
import com.youloft.watcher.pages.friend.dialog.FriendNumLimitDialog
import com.youloft.watcher.pages.friend.dialog.FriendPermissionsSettingsDialog
import com.youloft.watcher.pages.friend.dialog.UpdateRelationDialog
import com.youloft.watcher.pages.user.dialog.UpdateUsernameDialog
import com.youloft.watcher.view.RelationButton
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The rebuilt dialogs are exercised the way a user would: show them, click the real buttons, and
 * check what the caller was told.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FriendDialogsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Dismissal is asynchronous, like the recovered manager's, so the looper has to be drained. */
    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun <T : android.app.Dialog> T.shown(): T {
        show()
        assertNotNull(ShadowDialog.getLatestDialog())
        return this
    }

    @Test
    fun `confirm dialog shows the recovered defaults`() {
        val dialog = ConfirmDialog(context, "确认删除该好友吗？").shown()

        assertEquals("确认删除该好友吗？", dialog.findViewById<TextView>(R.id.tv_title).text.toString())
        assertEquals(
            context.getString(R.string.cancel),
            dialog.findViewById<TextView>(R.id.tv_cancel).text.toString(),
        )
        assertEquals(
            context.getString(R.string.confirm),
            dialog.findViewById<TextView>(R.id.tv_confirm).text.toString(),
        )
        assertEquals(android.view.View.GONE, dialog.findViewById<TextView>(R.id.tv_content).visibility)
    }

    @Test
    fun `confirm button reports and closes while cancel only closes`() {
        var confirmed = 0
        val cancelling = ConfirmDialog(context, "标题")
            .setConfirmText(R.string.confirm2)
            .setContent(R.string.apply_expired)
            .setOnConfirm { confirmed++ }
        cancelling.show()

        assertEquals(
            context.getString(R.string.confirm2),
            cancelling.findViewById<TextView>(R.id.tv_confirm).text.toString(),
        )
        assertEquals(android.view.View.VISIBLE, cancelling.findViewById<TextView>(R.id.tv_content).visibility)

        cancelling.findViewById<TextView>(R.id.tv_cancel).performClick()
        idle()
        assertFalse(cancelling.isShowing)
        assertEquals(0, confirmed, "the cancel path must not report a decision")

        val confirming = ConfirmDialog(context, "标题").setOnConfirm { confirmed++ }
        confirming.show()
        confirming.findViewById<TextView>(R.id.tv_confirm).performClick()
        idle()
        assertEquals(1, confirmed)
        assertFalse(confirming.isShowing)
    }

    @Test
    fun `confirm button ignores a second click inside the interval`() {
        var confirmed = 0
        val dialog = ConfirmDialog(context, "标题").setOnConfirm { confirmed++ }.shown()
        val button = dialog.findViewById<TextView>(R.id.tv_confirm)

        button.performClick()
        button.performClick()

        assertEquals(1, confirmed)
    }

    @Test
    fun `relation dialog applies only on confirm`() {
        var applied: Int? = null
        val cancelling = UpdateRelationDialog(context, 2) { applied = it }.shown()
        val picker = cancelling.findViewById<RelationButton>(R.id.relation_button)
        assertNotNull(picker)

        cancelling.findViewById<View>(R.id.label_relatives).performClick()
        assertNull(applied, "a tile click alone must not report a decision")

        cancelling.findViewById<View>(R.id.iv_close).performClick()
        idle()
        assertFalse(cancelling.isShowing)
        assertNull(applied)

        val confirming = UpdateRelationDialog(context, 2) { applied = it }
        confirming.show()
        confirming.findViewById<View>(R.id.label_relatives).performClick()
        confirming.findViewById<TextView>(R.id.tv_confirm).performClick()
        idle()
        assertEquals(3, applied)
        assertFalse(confirming.isShowing)
    }

    @Test
    fun `relation picker marks only the selected tile`() {
        val dialog = UpdateRelationDialog(context, 1, onConfirm = { }).shown()
        val picker = dialog.findViewById<RelationButton>(R.id.relation_button)

        assertEquals(
            android.view.View.VISIBLE,
            picker.findViewById<ImageView>(R.id.iv_lover_label).visibility,
        )
        assertEquals(
            android.view.View.GONE,
            picker.findViewById<ImageView>(R.id.iv_other_label).visibility,
        )
        assertEquals(
            android.view.View.VISIBLE,
            picker.findViewById<ImageView>(R.id.iv_lover).visibility,
            "the tick is shown on the selected tile",
        )
    }

    @Test
    fun `username dialog caps length, shows the counter and gates confirm`() {
        var submitted: String? = null
        val dialog = UpdateUsernameDialog(
            context,
            title = "修改备注",
            preset = "",
            hint = "请输入给Ta的备注",
            onConfirm = { submitted = it },
        ).shown()
        val editor = dialog.findViewById<android.widget.EditText>(R.id.et_username)
        val confirm = dialog.findViewById<TextView>(R.id.tv_confirm)

        assertFalse(confirm.isEnabled, "empty input must not be confirmable")

        editor.setText("新备注")
        assertEquals(
            context.getString(R.string.input_length_hint, 3, 10),
            dialog.findViewById<TextView>(R.id.tv_username_count).text.toString(),
        )
        assertTrue(confirm.isEnabled)

        confirm.performClick()
        idle()
        assertEquals("新备注", submitted)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `username dialog starts from the preset and always accepts when asked`() {
        var submitted: String? = null
        val dialog = UpdateUsernameDialog(
            context,
            title = "修改备注",
            preset = "旧备注",
            hint = "请输入给Ta的备注",
            alwaysEnabled = true,
            onConfirm = { submitted = it },
        ).shown()

        assertEquals("旧备注", dialog.findViewById<android.widget.EditText>(R.id.et_username).text.toString())
        assertTrue(dialog.findViewById<TextView>(R.id.tv_confirm).isEnabled)
        assertEquals(
            context.getString(R.string.input_length_hint, 3, 10),
            dialog.findViewById<TextView>(R.id.tv_username_count).text.toString(),
        )

        dialog.findViewById<TextView>(R.id.tv_confirm).performClick()
        idle()
        assertEquals("旧备注", submitted)
    }

    @Test
    fun `permission dialog reflects the incoming set and rebuilds it on confirm`() {
        val initial = Permission(sensLocation = true, sensTrack = true, sensCall = true)
        var result: Permission? = null
        val dialog = FriendPermissionsSettingsDialog(context, 1, initial) { result = it }.shown()

        assertTrue(dialog.findViewById<android.widget.CheckBox>(R.id.cb_sens_location).isChecked)
        assertTrue(dialog.findViewById<android.widget.CheckBox>(R.id.cb_sens_track).isChecked)
        assertTrue(dialog.findViewById<android.widget.CheckBox>(R.id.cb_sens_call).isChecked)
        assertFalse(dialog.findViewById<android.widget.CheckBox>(R.id.cb_sens_app_use).isChecked)

        dialog.findViewById<android.widget.CheckBox>(R.id.cb_sens_app_use).isChecked = true
        dialog.findViewById<TextView>(R.id.tv_confirm).performClick()
        idle()

        assertNotNull(result)
        assertTrue(result!!.sensAppUse)
        assertTrue(result!!.sensLocation)
        assertFalse(result!!.sensScreenShot, "the recovered dialog leaves the tenth permission alone")
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `closing the permission dialog reports no decision`() {
        var called = false
        val dialog = FriendPermissionsSettingsDialog(context, 1, Permission()) { called = true }
        dialog.show()

        dialog.findViewById<View>(R.id.iv_close).performClick()
        idle()

        assertFalse(dialog.isShowing)
        assertTrue(called, "the recovered dialog reported on dismiss")
    }

    @Test
    fun `permission dialog without a permission object dismisses itself`() {
        var called = false
        val dialog = FriendPermissionsSettingsDialog(context, 1, null) { called = true }
        dialog.show()
        idle()

        assertFalse(dialog.isShowing, "the recovered dialog closed immediately when there was nothing to show")
        assertTrue(called)
    }

    @Test
    fun `the permission dialog hides its close button when asked`() {
        val dialog = FriendPermissionsSettingsDialog(
            context,
            1,
            Permission(),
            showClose = false,
        ) { }
        dialog.show()

        assertEquals(
            android.view.View.GONE,
            dialog.findViewById<View>(R.id.iv_close).visibility,
        )
    }

    @Test
    fun `quota dialog reports the vip entry and closes`() {
        var opened = 0
        val dialog = FriendNumLimitDialog(context) { opened++ }.shown()

        dialog.findViewById<View>(R.id.tv_vip).performClick()
        idle()

        assertEquals(1, opened)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `quota dialog close button dismisses without opening the vip page`() {
        var opened = 0
        val dialog = FriendNumLimitDialog(context) { opened++ }.shown()

        dialog.findViewById<View>(R.id.iv_close).performClick()
        idle()

        assertEquals(0, opened)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `the relation widget ignores a repeat of the current relation`() {
        var reported = 0
        val button = RelationButton(context)
        button.onRelationChanged = { reported++ }

        button.setRelation(2)
        button.setRelation(2)

        assertEquals(1, reported, "the recovered widget returned early when the value did not change")
    }
}
