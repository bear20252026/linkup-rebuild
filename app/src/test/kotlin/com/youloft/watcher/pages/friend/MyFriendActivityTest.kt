package com.youloft.watcher.pages.friend

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.youloft.watcher.R
import com.youloft.watcher.bean.CmdFriendBean
import com.youloft.watcher.pages.friend.FriendFlowFixtures.Recorder
import com.youloft.watcher.pages.friend.FriendFlowFixtures.TestApplication
import com.youloft.watcher.pages.friend.FriendFlowFixtures.friend
import kotlin.test.assertEquals
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
 * Regression net for the rebuilt my-friend list: the recovered ordering, the self-closing empty
 * behaviour, the row routing, and the push channel's filter — all against the real view tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class MyFriendActivityTest {

    private val source get() = FriendFlowFixtures.source

    @Before
    fun setUp() {
        FriendFlowFixtures.reset()
    }

    private fun launch(): MyFriendActivity {
        val controller = Robolectric.buildActivity(MyFriendActivity::class.java)
        controller.create().start().resume()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return controller.get()
    }

    private fun MyFriendActivity.list() = window.decorView.findViewById<RecyclerView>(R.id.rv_friend_list)

    private fun MyFriendActivity.friendAdapter() = list().adapter as com.youloft.watcher.adapter.FriendListVAdapter

    private fun rowAt(activity: MyFriendActivity, position: Int) =
        activity.list().findViewHolderForAdapterPosition(position)!!.itemView

    private fun measure(activity: MyFriendActivity) {
        val rv = activity.list()
        rv.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
        )
        rv.layout(0, 0, 1080, 2400)
    }

    @Test
    fun `title counts friends and the main account is put first`() {
        source.mainUser = 22L
        source.publish(listOf(friend(11), friend(22), friend(33)))
        val activity = launch()

        assertEquals(
            activity.getString(R.string.my_friend_num, 3),
            activity.window.decorView.findViewById<TextView>(R.id.tv_title).text.toString(),
        )
        assertEquals(listOf(22L, 11L, 33L), activity.friendAdapter().items.map { it.userId })
    }

    @Test
    fun `entries without a real id are dropped before rendering`() {
        source.publish(listOf(friend(11), friend(0)))
        val activity = launch()

        assertEquals(listOf(11L), activity.friendAdapter().items.map { it.userId })
    }

    @Test
    fun `an empty channel closes the screen itself`() {
        source.publish(emptyList())
        val activity = launch()

        assertTrue(activity.isFinishing, "the recovered screen finishes when the list is empty")
    }

    @Test
    fun `a list of only placeholder rows leaves zero friends and hides the add button`() {
        source.publish(listOf(friend(0)))
        val activity = launch()

        val adapter = activity.friendAdapter()
        // BRVAH's state view occupies the one item slot when the data list is empty, so the data
        // count is what says "no friends"; the visible slot count is not.
        assertEquals(0, adapter.items.size)
        assertEquals(
            activity.getString(R.string.my_friend_num, 0),
            activity.window.decorView.findViewById<TextView>(R.id.tv_title).text.toString(),
        )
        assertEquals(View.GONE, activity.window.decorView.findViewById<View>(R.id.tv_add_friend).visibility)
        assertTrue(!activity.isFinishing)
    }

    @Test
    fun `row shows the remark when there is one and the relation text`() {
        source.publish(listOf(friend(11, nickname = "网友11", remark = "备注11", relation = 1)))
        val activity = launch()
        measure(activity)

        val row = rowAt(activity, 0)
        assertEquals("备注11", row.findViewById<TextView>(R.id.tv_name).text.toString())
        assertEquals("恋人", row.findViewById<TextView>(R.id.tv_relation_desc).text.toString())
        assertEquals(View.GONE, row.findViewById<View>(R.id.iv_sex).visibility)
        assertEquals(View.GONE, row.findViewById<View>(R.id.tv_add_date).visibility)
        assertEquals(View.GONE, row.findViewById<View>(R.id.cb_select).visibility)
    }

    @Test
    fun `row click opens the friend info screen with the recovered type and name`() {
        source.publish(listOf(friend(11, remark = "备注11")))
        val activity = launch()
        val recorder = Recorder()
        activity.navigation = recorder
        measure(activity)

        rowAt(activity, 0).performClick()

        assertEquals(listOf("info:11:2:备注11"), recorder.events)
    }

    @Test
    fun `a deregistered friend is refused with its own copy`() {
        source.publish(listOf(friend(11, unregisterTime = 1_700_000_000L)))
        val activity = launch()
        val recorder = Recorder()
        activity.navigation = recorder
        measure(activity)

        rowAt(activity, 0).performClick()

        assertTrue(recorder.events.isEmpty())
        assertEquals(
            activity.getString(R.string.user_done_unregister),
            ShadowToast.getTextOfLatestToast()?.toString(),
        )
    }

    @Test
    fun `add friend buttons carry their own recovered modes`() {
        source.publish(listOf(friend(11)))
        val activity = launch()
        val recorder = Recorder()
        activity.navigation = recorder

        activity.window.decorView.findViewById<View>(R.id.tv_add_friend).performClick()
        assertEquals(listOf("addFriend:6"), recorder.events)

        val emptyButton = activity.friendAdapter().stateView
            ?.findViewById<View>(R.id.tv_empty_button)
        assertNotNull(emptyButton)
        emptyButton.performClick()
        assertEquals(listOf("addFriend:6", "addFriend:0"), recorder.events)
    }

    @Test
    fun `add friend clicks are throttled`() {
        source.publish(listOf(friend(11)))
        val activity = launch()
        val recorder = Recorder()
        activity.navigation = recorder

        activity.onAddFriendClick()
        activity.onAddFriendClick()

        assertEquals(listOf("addFriend:6"), recorder.events)
    }

    @Test
    fun `pushes for adding do not touch the local refresh`() {
        source.publish(listOf(friend(11)))
        val activity = launch()

        source.publishCommand(CmdFriendBean(type = CmdFriendBean.ADD, userid = 99L))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(source.handled.isEmpty())
        assertEquals(0, source.clears)

        source.publishCommand(CmdFriendBean(type = CmdFriendBean.ADD_SELF, userid = 99L))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(source.handled.isEmpty())
    }

    @Test
    fun `a delete push is consumed once and clears the channel`() {
        source.publish(listOf(friend(11)))
        val activity = launch()

        source.publishCommand(CmdFriendBean(type = CmdFriendBean.DELETE, userid = 99L))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf(99L), source.handled.map { it.userid })
        assertEquals(1, source.clears)
        assertNull(source.friendCommand.value)
    }

    @Test
    fun `the empty state view is enabled with the recovered layout`() {
        source.publish(listOf(friend(11)))
        val activity = launch()

        assertTrue(activity.friendAdapter().isStateViewEnable)
        assertNotNull(activity.friendAdapter().stateView)
    }

    @Test
    fun `back finishes`() {
        source.publish(listOf(friend(11)))
        val activity = launch()

        activity.window.decorView.findViewById<View>(R.id.iv_back).performClick()

        assertTrue(activity.isFinishing)
    }

    @Test
    fun `the graph hands every caller the same friend source instance`() {
        // A `get()` that builds a new source would let one caller observe a list nobody publishes to.
        val app = com.youloft.watcher.data.LinkUpApplication()

        kotlin.test.assertSame(app.friendList, app.friendList)
    }
}
