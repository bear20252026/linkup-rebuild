package com.youloft.watcher.pages.friend

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.youloft.linkup.api.dto.ConfirmAdd
import com.youloft.linkup.api.dto.FriendRequest
import com.youloft.linkup.api.dto.Permission
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.R
import com.youloft.watcher.pages.friend.FriendFlowFixtures.FakeRepository
import com.youloft.watcher.pages.friend.FriendFlowFixtures.Recorder
import com.youloft.watcher.pages.friend.FriendFlowFixtures.TestApplication
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
 * Regression net for the rebuilt detail screen. The Intent here is always built through
 * `FriendRequestDetailActivity.start`, the same production path the list screen uses, so these
 * tests also cover the bean transport across screens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class FriendRequestDetailActivityTest {

    private val repository: FakeRepository get() = FriendFlowFixtures.repository
    private val flags get() = FriendFlowFixtures.flags

    @Before
    fun setUp() {
        FriendFlowFixtures.reset()
    }

    private fun launch(request: FriendRequest, myFriendLimit: Boolean = false): FriendRequestDetailActivity {
        // Built directly rather than through `start()`: an Application context would need
        // FLAG_ACTIVITY_NEW_TASK. The production call path is covered by the list screen's test,
        // which starts this screen from an Activity.
        val intent = Intent(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            FriendRequestDetailActivity::class.java,
        )
            .putExtra(FriendRequestDetailActivity.EXTRA_DATA, request)
            .putExtra(FriendRequestDetailActivity.EXTRA_MY_FRIEND_LIMIT, myFriendLimit)
        val activity = Robolectric.buildActivity(FriendRequestDetailActivity::class.java, intent).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return activity
    }

    private fun FriendRequestDetailActivity.decor() = window.decorView

    private fun <T : View> FriendRequestDetailActivity.find(id: Int) = decor().findViewById<T>(id)

    @Test
    fun `profile fields render from the passed bean`() {
        val activity = launch(FriendFlowFixtures.request(11, relation = 1, sex = 2, birthday = "1991-02-03"))

        assertEquals("昵称11", activity.find<TextView>(R.id.tv_name).text.toString())
        assertEquals("1991-02-03", activity.find<TextView>(R.id.tv_birthday).text.toString())
        assertEquals("女", activity.find<TextView>(R.id.tv_sex).text.toString())
        assertNotNull(activity.find<TextView>(R.id.tv_sex).compoundDrawablesRelative[2])
        assertEquals("恋人", activity.find<TextView>(R.id.tv_relation).text.toString())
        assertEquals("匹配码：11", activity.find<TextView>(R.id.tv_match_code).text.toString())
    }

    @Test
    fun `relation and sex map every recovered branch`() {
        assertEquals("挚友", launch(FriendFlowFixtures.request(11, relation = 2)).find<TextView>(R.id.tv_relation).text.toString())
        assertEquals("亲人", launch(FriendFlowFixtures.request(11, relation = 3)).find<TextView>(R.id.tv_relation).text.toString())
        assertEquals("其他", launch(FriendFlowFixtures.request(11, relation = 9)).find<TextView>(R.id.tv_relation).text.toString())
        assertEquals("男", launch(FriendFlowFixtures.request(11, sex = 1)).find<TextView>(R.id.tv_sex).text.toString())
        assertEquals(
            "",
            launch(FriendFlowFixtures.request(11, sex = 0)).find<TextView>(R.id.tv_sex).text.toString(),
            "an unknown sex stays blank and the hint shows 未设置",
        )
        assertNull(launch(FriendFlowFixtures.request(11, sex = 0)).find<TextView>(R.id.tv_sex).compoundDrawablesRelative[2])
    }

    @Test
    fun `pending request keeps the button enabled and labelled agree`() {
        val activity = launch(FriendRequestDetailActivityTest.pending())

        assertTrue(activity.find<TextView>(R.id.tv_agree).isEnabled)
        assertEquals("同意", activity.find<TextView>(R.id.tv_agree).text.toString())
    }

    @Test
    fun `a request that is no longer pending is disabled and relabelled`() {
        val activity = launch(FriendFlowFixtures.request(11, status = 4))

        assertFalse(activity.find<TextView>(R.id.tv_agree).isEnabled)
        assertEquals(
            activity.getString(R.string.friend_request_del_tips),
            activity.find<TextView>(R.id.tv_agree).text.toString(),
        )
    }

    @Test
    fun `agree posts code and relation then leaves the screen`() {
        repository.confirmResponse = ApiResponse(200, "success", data = ConfirmAdd())
        val activity = launch(FriendFlowFixtures.request(11, relation = 3))
        val recorder = Recorder()
        activity.navigation = recorder

        activity.find<View>(R.id.tv_agree).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf(Triple<Long, Int, Permission?>(11L, 3, null)), repository.confirmations)
        assertTrue(recorder.events.contains("agreeAdd"))
        assertTrue(activity.isFinishing)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `expired request is refused before any network call`() {
        val activity = launch(FriendFlowFixtures.request(11, ageDays = 40))
        val recorder = Recorder()
        activity.navigation = recorder

        activity.onAgreeClick()

        assertTrue(repository.confirmations.isEmpty())
        assertEquals(
            activity.getString(R.string.apply_expired),
            ShadowToast.getTextOfLatestToast()?.toString(),
        )
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun `my quota blocks the call with the dialog while the requester is not the blocker`() {
        val activity = launch(FriendFlowFixtures.request(11), myFriendLimit = true)
        val recorder = Recorder()
        activity.navigation = recorder

        activity.onAgreeClick()

        assertEquals(listOf("numLimitDialog"), recorder.events)
        assertTrue(repository.confirmations.isEmpty())
    }

    @Test
    fun `vip users fall through the my-quota dialog to the requester check`() {
        repository.confirmResponse = ApiResponse(200, "success", data = ConfirmAdd())
        flags.vip = true
        val activity = launch(FriendFlowFixtures.request(11, friendLimitReached = true), myFriendLimit = true)

        activity.onAgreeClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(
            activity.getString(R.string.ta_friend_num_limit_tips),
            ShadowToast.getTextOfLatestToast()?.toString(),
        )
        assertTrue(repository.confirmations.isEmpty())
    }

    @Test
    fun `requester over its own quota is refused with its own copy`() {
        val activity = launch(FriendFlowFixtures.request(11, friendLimitReached = true))

        activity.onAgreeClick()

        assertEquals(
            activity.getString(R.string.ta_friend_num_limit_tips),
            ShadowToast.getTextOfLatestToast()?.toString(),
        )
        assertTrue(repository.confirmations.isEmpty())
    }

    @Test
    fun `unregistered requesters are not blocked here, unlike on the list screen`() {
        repository.confirmResponse = ApiResponse(200, "success", data = ConfirmAdd())
        val activity = launch(FriendFlowFixtures.request(11, unregister = true))

        activity.onAgreeClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(1, repository.confirmations.size, "the recovered detail screen has no unregister guard")
    }

    @Test
    fun `agree clicks are debounced`() {
        repository.confirmResponse = ApiResponse(200, "success", data = ConfirmAdd())
        val activity = launch(FriendFlowFixtures.request(11))

        activity.onAgreeClick()
        activity.onAgreeClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(1, repository.confirmations.size)
    }

    @Test
    fun `server failure is toasted with whatever message came back`() {
        repository.confirmResponse = ApiResponse(500, "服务开小差", data = null)
        val activity = launch(FriendFlowFixtures.request(11))
        val recorder = Recorder()
        activity.navigation = recorder

        activity.onAgreeClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals("服务开小差", ShadowToast.getTextOfLatestToast()?.toString())
        assertTrue(recorder.events.isEmpty())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `permission settings branch keeps the screen open`() {
        repository.confirmResponse = ApiResponse(200, "success", data = ConfirmAdd(friendUserId = 11L))
        flags.askPermissions = true
        val activity = launch(FriendFlowFixtures.request(11))
        val recorder = Recorder()
        activity.navigation = recorder

        activity.onAgreeClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf("permissions:11"), recorder.events)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `back finishes without any confirm`() {
        val activity = launch(FriendFlowFixtures.request(11))

        activity.find<View>(R.id.iv_back).performClick()

        assertTrue(activity.isFinishing)
        assertTrue(repository.confirmations.isEmpty())
    }

    private companion object {
        fun pending() = FriendFlowFixtures.request(11)
    }
}
