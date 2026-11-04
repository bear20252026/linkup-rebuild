package com.youloft.watcher.pages.friend

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.youloft.linkup.api.dto.Friend
import com.youloft.watcher.R
import com.youloft.watcher.pages.friend.dialog.VerifyInfoDialog
import com.youloft.watcher.view.RelationButton
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The verify sheet is the hinge of the add path: it decides which relation is pre-selected, and it is
 * the only place a friend-quota wall can stop the request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VerifyInfoDialogTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun sheet(
        friend: Friend = Friend(userId = 777L, nickname = "网友", sex = "1"),
        mySex: Int = 0,
        onVip: (Int, String) -> Unit = { _, _ -> },
    ): VerifyInfoDialog {
        val dialog = VerifyInfoDialog(
            context,
            friend,
            source = 2,
            mySex = mySex,
            onVipLabelClick = onVip,
            onConfirm = { _, _ -> },
        )
        dialog.show()
        assertNotNull(ShadowDialog.getLatestDialog())
        return dialog
    }

    private fun <T : View> find(dialog: android.app.Dialog, id: Int): T = dialog.findViewById(id)

    @Test
    fun `the sheet renders the stranger as the recovered screen did`() {
        val dialog = sheet(Friend(userId = 777L, nickname = "网友", sex = "2", avatar = null))

        assertEquals("网友", find<TextView>(dialog, R.id.tv_name).text.toString())
        assertEquals(
            context.getString(R.string.match_code_num, 777L),
            find<TextView>(dialog, R.id.tv_match_code).text.toString(),
        )
        assertTrue(find<TextView>(dialog, R.id.tv_name).compoundDrawables[2] != null)
    }

    @Test
    fun `the relation is pre-selected from the pair, not from the picker`() {
        // Other male: my male -> 挚友, my female -> 恋人, unknown -> 其他.
        assertEquals(2, relationFor(otherSex = "1", mySex = 1))
        assertEquals(1, relationFor(otherSex = "1", mySex = 2))
        assertEquals(4, relationFor(otherSex = "1", mySex = 0))

        // Other female: the mirror image.
        assertEquals(1, relationFor(otherSex = "2", mySex = 1))
        assertEquals(2, relationFor(otherSex = "2", mySex = 2))
        assertEquals(4, relationFor(otherSex = "2", mySex = 0))

        // Unknown sex clears the icon and lands on 其他.
        assertEquals(4, relationFor(otherSex = null, mySex = 1))
    }

    private fun relationFor(otherSex: String?, mySex: Int): Int {
        val dialog = VerifyInfoDialog(
            context,
            Friend(userId = 1L, nickname = "n", sex = otherSex),
            source = 1,
            mySex = mySex,
            onVipLabelClick = { _, _ -> },
            onConfirm = { _, _ -> },
        )
        dialog.show()
        val picked = dialog.pickedRelation()
        dialog.dismissDelayed()
        idle()
        return picked
    }

    @Test
    fun `a stranger at their quota gets the membership label instead of a button`() {
        val vip = mutableListOf<Pair<Int, String>>()
        val dialog = sheet(Friend(userId = 8L, nickname = "上限", isFriendLimit = true), onVip = { s, l -> vip += s to l })

        assertEquals(View.GONE, find<View>(dialog, R.id.tv_confirm).visibility)
        assertEquals(View.VISIBLE, find<View>(dialog, R.id.iv_label_vip).visibility)

        find<View>(dialog, R.id.iv_label_vip).performClick()
        idle()

        assertEquals(listOf(16 to "多个好友"), vip)
    }

    @Test
    fun `confirming reports the picked relation`() {
        val picked = mutableListOf<Int>()
        val dialog = VerifyInfoDialog(
            context,
            Friend(userId = 9L, nickname = "网友", sex = "1"),
            source = 3,
            mySex = 0,
            onVipLabelClick = { _, _ -> },
            onConfirm = { relation, _ -> picked += relation },
        )
        dialog.show()
        dialog.findViewById<RelationButton>(R.id.relation_button).setRelation(3)

        dialog.findViewById<View>(R.id.tv_confirm).performClick()
        idle()

        assertEquals(listOf(3), picked)
    }

    @Test
    fun `the quota wall swallows the confirm tap`() {
        val picked = mutableListOf<Int>()
        val dialog = VerifyInfoDialog(
            context,
            Friend(userId = 9L, nickname = "网友", sex = "1", isFriendLimit = true),
            source = 3,
            mySex = 0,
            onVipLabelClick = { _, _ -> },
            onConfirm = { relation, _ -> picked += relation },
        )
        dialog.show()
        // The button is hidden, but the recovered listener still guarded on the flag, so tap the view.
        dialog.findViewById<View>(R.id.tv_confirm).performClick()
        idle()

        assertTrue(picked.isEmpty())
    }

    @Test
    fun `each caller is named the way the original reported it`() {
        assertEquals("扫一扫", VerifyInfoDialog.sourceLabel(1))
        assertEquals("搜索匹配码", VerifyInfoDialog.sourceLabel(2))
        assertEquals("搜索手机号", VerifyInfoDialog.sourceLabel(3))
        assertEquals("手机通讯录", VerifyInfoDialog.sourceLabel(4))
        assertEquals("H5", VerifyInfoDialog.sourceLabel(5))
        assertEquals("", VerifyInfoDialog.sourceLabel(0))
    }
}
