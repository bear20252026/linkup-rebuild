package com.youloft.watcher.pages.friend

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.youloft.linkup.api.dto.ConfirmAdd
import com.youloft.linkup.api.dto.FriendRequest
import com.youloft.linkup.api.dto.FriendRequestList
import com.youloft.linkup.api.dto.Permission
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.R
import com.youloft.watcher.data.FriendRequestRepository
import com.youloft.watcher.data.LinkUpApplication
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * Regression net for the first rebuilt screen: the recovered row state machine, the paging guards,
 * the four agree pre-conditions and the confirm round-trip, all without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = FriendRequestListActivityTest.TestApplication::class)
class FriendRequestListActivityTest {

    companion object {
        var repository = FakeRepository()
        val flags = Flags()
    }

    class Flags {
        var vip = false
        var cachedFriends = 0
        var askPermissions = false
    }

    class TestApplication : LinkUpApplication() {
        override val friendRequests: FriendRequestRepository get() = repository
        override val isVip: Boolean get() = flags.vip
        override val cachedFriendCount: Int get() = flags.cachedFriends
        override val askPermissionsAfterAdd: Boolean get() = flags.askPermissions
    }

    class FakeRepository : FriendRequestRepository {
        val listRequests = mutableListOf<Pair<Int, Int>>()
        val confirmations = mutableListOf<Triple<Long, Int, Permission?>>()
        var firstPage: List<FriendRequest?> = emptyList()
        var nextPage: List<FriendRequest?> = emptyList()
        var firstPageLimit = false
        var confirmResponse = ApiResponse(200, "success", data = ConfirmAdd())
        var networkFailure: Throwable? = null

        override suspend fun applyList(page: Int, pageSize: Int): ApiResponse<FriendRequestList> {
            listRequests += page to pageSize
            networkFailure?.let { throw it }
            val list = if (page == 1) firstPage else nextPage
            return ApiResponse(
                200,
                "success",
                data = FriendRequestList(
                    list = list,
                    isFriendLimit = if (page == 1) firstPageLimit else null,
                ),
            )
        }

        override suspend fun confirmAdd(
            userId: Long,
            relation: Int,
            sensSetting: Permission?,
        ): ApiResponse<ConfirmAdd> {
            confirmations += Triple(userId, relation, sensSetting)
            return confirmResponse
        }
    }

    class Recorder : FriendRequestNavigation {
        val events = mutableListOf<String>()
        override fun openAddFriend() { events += "addFriend" }

        override fun openFriendRequestDetail(request: FriendRequest, friendLimit: Boolean) {
            events += "detail:${request.userId}:$friendLimit"
        }

        override fun openFriendInfo(userId: Long) { events += "info:$userId" }
        override fun openAgreeAdd() { events += "agreeAdd" }
        override fun showFriendNumLimitDialog() { events += "numLimitDialog" }
        override fun showPermissionSettings(friendUserId: Long) { events += "permissions:$friendUserId" }
    }

    private val day = 86_400_000L
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        repository = FakeRepository()
        flags.vip = false
        flags.cachedFriends = 0
        flags.askPermissions = false
    }

    private fun request(
        userId: Long,
        status: Int = 0,
        ageDays: Long = 0,
        riskLevel: Int? = null,
        riskTypes: List<Int?>? = null,
        unregister: Boolean = false,
        friendLimitReached: Boolean = false,
        relation: Int = 2,
    ) = FriendRequest(
        userId = userId,
        nickname = "昵称$userId",
        relation = relation,
        status = status,
        createAt = (now - ageDays * day) / 1000L,
        riskLevel = riskLevel,
        riskTypes = riskTypes,
        isUnregister = unregister,
        isFriendLimit = friendLimitReached,
    )

    /** Brings the screen up and forces a layout so the rows are real views, not stubs. */
    private fun launch(): Pair<FriendRequestListActivity, RecyclerView> {
        val activity = Robolectric.buildActivity(FriendRequestListActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val recyclerView = activity.window.decorView.findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
        )
        recyclerView.layout(0, 0, 1080, 2400)
        return activity to recyclerView
    }

    private fun FriendRequestListActivity.listAdapter() =
        (window.decorView.findViewById<RecyclerView>(R.id.recyclerView).adapter as FriendRequestAdapter)

    private fun rowAt(recyclerView: RecyclerView, position: Int) =
        recyclerView.findViewHolderForAdapterPosition(position)!!.itemView

    @Test
    fun `row state follows the recovered branches`() {
        val pending = rowState(request(1, status = 0, ageDays = 3), now, isFirst = true, isLast = false)
        assertEquals(RowVisibility.VISIBLE, pending.agree)
        assertEquals(RowVisibility.INVISIBLE, pending.expired)
        assertEquals(RowVisibility.INVISIBLE, pending.added)
        assertEquals(RowVisibility.VISIBLE, pending.divider)
        assertTrue(pending.roundLeft)
        assertFalse(pending.roundRight)

        val expired = rowState(request(2, status = 0, ageDays = 40), now, isFirst = false, isLast = true)
        assertEquals(RowVisibility.VISIBLE, expired.expired)
        assertEquals(RowVisibility.INVISIBLE, expired.agree)
        assertEquals(RowVisibility.INVISIBLE, expired.divider)
        assertTrue(expired.roundRight)

        val added = rowState(request(3, status = 1), now, isFirst = false, isLast = false)
        assertEquals(RowVisibility.VISIBLE, added.added)
        assertEquals(RowVisibility.GONE, added.agree)
        assertEquals(RowVisibility.VISIBLE, rowState(request(4, status = 4), now, false, false).added)

        // Statuses outside {0,1,4} reach the agree branch even when older than 30 days.
        assertEquals(RowVisibility.VISIBLE, rowState(request(5, status = 2, ageDays = 60), now, false, false).agree)

        val risky = rowState(request(6, riskLevel = 3, riskTypes = listOf(4)), now, false, false)
        assertTrue(risky.warning)
        assertTrue(risky.warningTips)
        val riskless = rowState(request(7, riskLevel = 1, riskTypes = listOf(4)), now, false, false)
        assertFalse(riskless.warning)
        assertFalse(riskless.warningTips)
    }

    @Test
    fun `first page is requested with the recovered page size`() {
        repository.firstPage = listOf(request(11), request(22, status = 1))
        val (_, recyclerView) = launch()

        assertEquals(listOf(1 to 20), repository.listRequests)
        assertEquals(2, recyclerView.adapter!!.itemCount)
    }

    @Test
    fun `rows render nickname and action state`() {
        repository.firstPage = listOf(request(11), request(22, status = 1))
        val (_, recyclerView) = launch()

        val pendingRow = rowAt(recyclerView, 0)
        assertEquals("昵称11", pendingRow.findViewById<TextView>(R.id.tv_name).text.toString())
        assertEquals(View.VISIBLE, pendingRow.findViewById<View>(R.id.tv_agree).visibility)
        assertEquals(View.INVISIBLE, pendingRow.findViewById<View>(R.id.tv_added).visibility)
        assertEquals(View.VISIBLE, pendingRow.findViewById<View>(R.id.view_divider).visibility)

        val addedRow = rowAt(recyclerView, 1)
        assertEquals(View.VISIBLE, addedRow.findViewById<View>(R.id.tv_added).visibility)
        assertEquals(View.GONE, addedRow.findViewById<View>(R.id.tv_agree).visibility)
        assertEquals(View.INVISIBLE, addedRow.findViewById<View>(R.id.view_divider).visibility)
    }

    @Test
    fun `risk row shows its warning band`() {
        repository.firstPage = listOf(request(11, riskLevel = 3, riskTypes = listOf(4)))
        val (activity, recyclerView) = launch()

        val warning = rowAt(recyclerView, 0).findViewById<View>(R.id.ll_warning)
        assertEquals(View.VISIBLE, warning.visibility)
        assertEquals(
            activity.getString(R.string.add_multiple_friends_recently),
            warning.findViewById<TextView>(R.id.tv_warning_tips).text.toString(),
        )
    }

    @Test
    fun `empty list shows the shared empty view`() {
        repository.firstPage = emptyList()
        val (activity, _) = launch()

        assertEquals(View.VISIBLE, activity.window.decorView.findViewById<View>(R.id.layout_empty).visibility)
    }

    @Test
    fun `a full page keeps paging and a short page stops it`() {
        repository.firstPage = (1..20).map { request(it.toLong()) }
        repository.nextPage = listOf(request(99))
        val (activity, _) = launch()

        assertEquals(listOf(1 to 20), repository.listRequests)
        activity.loadPage()
        assertEquals(listOf(1 to 20, 2 to 20), repository.listRequests)

        activity.loadPage()
        assertEquals(2, repository.listRequests.size, "a short page must exhaust paging")
    }

    @Test
    fun `network failure is toasted and the page is retried, not skipped`() {
        repository.networkFailure = IllegalStateException("offline")
        val (activity, recyclerView) = launch()

        assertEquals(
            activity.getString(R.string.tips_net_err),
            ShadowToast.getTextOfLatestToast()?.toString(),
        )
        assertEquals(0, recyclerView.adapter!!.itemCount)

        activity.loadPage()
        assertEquals(listOf(1 to 20, 1 to 20), repository.listRequests)
    }

    @Test
    fun `agree through the row button posts code and relation then marks the row added`() {
        repository.firstPage = listOf(request(11, relation = 3))
        repository.confirmResponse =
            ApiResponse(200, "success", data = ConfirmAdd(friendUserId = 11L, friendLimit = 0))
        flags.cachedFriends = 5
        val (activity, recyclerView) = launch()
        val recorder = Recorder()
        activity.navigation = recorder

        rowAt(recyclerView, 0).findViewById<View>(R.id.tv_agree).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf(Triple<Long, Int, Permission?>(11L, 3, null)), repository.confirmations)
        assertEquals(1, activity.listAdapter().getItem(0)?.status)
        assertTrue(recorder.events.contains("agreeAdd"))
        assertTrue(activity.isFinishing)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `expired request is refused before any network call`() {
        repository.firstPage = listOf(request(11, ageDays = 40))
        val (activity, _) = launch()
        val recorder = Recorder()
        activity.navigation = recorder

        activity.onAgreeClick(0)

        assertTrue(repository.confirmations.isEmpty())
        assertEquals(
            activity.getString(R.string.apply_expired),
            ShadowToast.getTextOfLatestToast()?.toString(),
        )
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun `quota dialog wins over the confirm call for non vip users`() {
        repository.firstPage = listOf(request(11))
        repository.firstPageLimit = true
        val (activity, _) = launch()
        val recorder = Recorder()
        activity.navigation = recorder

        activity.onAgreeClick(0)

        assertEquals(listOf("numLimitDialog"), recorder.events)
        assertTrue(repository.confirmations.isEmpty())
    }

    @Test
    fun `vip users are not blocked by the quota flag`() {
        repository.firstPage = listOf(request(11))
        repository.firstPageLimit = true
        flags.vip = true
        val (activity, _) = launch()

        activity.onAgreeClick(0)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(1, repository.confirmations.size)
    }

    @Test
    fun `unregistered requester is refused with its own copy`() {
        repository.firstPage = listOf(request(11, unregister = true))
        val (activity, _) = launch()

        activity.onAgreeClick(0)

        assertEquals(
            activity.getString(R.string.user_done_unregister),
            ShadowToast.getTextOfLatestToast()?.toString(),
        )
        assertTrue(repository.confirmations.isEmpty())
    }

    @Test
    fun `requester at its own quota is refused with its own copy`() {
        repository.firstPage = listOf(request(11, friendLimitReached = true))
        val (activity, _) = launch()

        activity.onAgreeClick(0)

        assertEquals(
            activity.getString(R.string.ta_friend_num_limit_tips),
            ShadowToast.getTextOfLatestToast()?.toString(),
        )
        assertTrue(repository.confirmations.isEmpty())
    }

    @Test
    fun `agree clicks are debounced`() {
        repository.firstPage = listOf(request(11))
        repository.confirmResponse =
            ApiResponse(200, "success", data = ConfirmAdd(friendUserId = 11L, friendLimit = 0))
        val (activity, _) = launch()

        activity.onAgreeClick(0)
        activity.onAgreeClick(0)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(1, repository.confirmations.size)
    }

    @Test
    fun `permission settings branch is taken when the channel asks for it`() {
        repository.firstPage = listOf(request(11))
        repository.confirmResponse =
            ApiResponse(200, "success", data = ConfirmAdd(friendUserId = 11L, friendLimit = 3))
        flags.askPermissions = true
        val (activity, _) = launch()
        val recorder = Recorder()
        activity.navigation = recorder

        activity.onAgreeClick(0)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf("permissions:11"), recorder.events)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `row click routes by status`() {
        repository.firstPage = listOf(
            request(11, status = 0),
            request(22, status = 1),
            request(33, status = 4),
            request(44, status = 2),
        )
        repository.firstPageLimit = false
        val (activity, _) = launch()
        val recorder = Recorder()
        activity.navigation = recorder

        activity.onRowClick(0)
        activity.onRowClick(1)
        activity.onRowClick(2)
        activity.onRowClick(3)

        assertEquals(listOf("detail:11:false", "info:22", "detail:33:false"), recorder.events)
    }

    @Test
    fun `title bar back and add friend are wired`() {
        repository.firstPage = listOf(request(11))
        val (activity, _) = launch()
        val recorder = Recorder()
        activity.navigation = recorder

        activity.window.decorView.findViewById<View>(R.id.iv_add_friend).performClick()
        assertEquals(listOf("addFriend"), recorder.events)

        activity.window.decorView.findViewById<View>(R.id.iv_back).performClick()
        assertTrue(activity.isFinishing)
    }
}
