package com.youloft.watcher.pages.friend

import com.youloft.linkup.api.dto.Birthday
import com.youloft.linkup.api.dto.ConfirmAdd
import com.youloft.linkup.api.dto.FriendInfo
import com.youloft.linkup.api.dto.FriendList
import com.youloft.linkup.api.dto.FriendRequest
import com.youloft.linkup.api.dto.FriendRequestList
import com.youloft.linkup.api.dto.Permission
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.bean.CmdFriendBean
import com.youloft.watcher.data.FriendListSource
import com.youloft.watcher.data.FriendRepository
import com.youloft.watcher.data.LinkUpApplication
import com.youloft.watcher.data.PendingFriendListSource

/**
 * Shared fixtures for the rebuilt friend-request flow. Robolectric builds the Application outside
 * the test instance, so the doubles it hands out live in one holder that both screens reset.
 */
object FriendFlowFixtures {
    var repository = FakeRepository()
    var source = TestFriendListSource()
    val flags = Flags()

    fun reset() {
        repository = FakeRepository()
        source = TestFriendListSource()
        flags.vip = false
        flags.cachedFriends = 0
        flags.askPermissions = false
    }

    /** Records what screens push through the friend-list channel; `handleFriendCommand` belongs to the IM card. */
    class TestFriendListSource : PendingFriendListSource() {
        var mainUser = 0L
        val handled = mutableListOf<CmdFriendBean>()
        var clears = 0

        override fun mainUserId(): Long = mainUser

        override fun clearFriendCommand() {
            clears++
            super.clearFriendCommand()
        }

        override fun handleFriendCommand(context: android.content.Context, command: CmdFriendBean) {
            handled += command
        }
    }

    fun friend(
        userId: Long,
        nickname: String? = "网友$userId",
        remark: String? = null,
        relation: Int = 2,
        sex: Int = 1,
        createAt: Long = 1_700_000_000L,
        unregisterTime: Long = 0L,
        permissionValue: Boolean = false,
    ) = FriendList.Info(
        userId = userId,
        nickname = nickname,
        remark = remark,
        relation = relation,
        sex = sex,
        createAt = createAt,
        unregisterTime = unregisterTime,
        permissionValue = permissionValue,
    )

    private const val DAY_MILLIS = 86_400_000L

    /** `ageDays` is measured from the real clock, because the screens read `System.currentTimeMillis`. */
    fun request(
        userId: Long,
        status: Int = 0,
        ageDays: Long = 0,
        riskLevel: Int? = null,
        riskTypes: List<Int?>? = null,
        unregister: Boolean = false,
        friendLimitReached: Boolean = false,
        relation: Int = 2,
        sex: Int = 1,
        birthday: String? = "1990-01-01",
        nowMillis: Long = System.currentTimeMillis(),
    ) = FriendRequest(
        userId = userId,
        nickname = "昵称$userId",
        relation = relation,
        status = status,
        createAt = (nowMillis - ageDays * DAY_MILLIS) / 1000L,
        riskLevel = riskLevel,
        riskTypes = riskTypes,
        isUnregister = unregister,
        isFriendLimit = friendLimitReached,
        sex = sex,
        birthday = birthday?.let { Birthday(date = it) },
    )

    class Flags {
        var vip = false
        var cachedFriends = 0
        var askPermissions = false
    }

    class TestApplication : LinkUpApplication() {
        override val friends: FriendRepository get() = repository
        override val friendList: FriendListSource get() = source
        override val isVip: Boolean get() = flags.vip
        override val cachedFriendCount: Int get() = flags.cachedFriends
        override val askPermissionsAfterAdd: Boolean get() = flags.askPermissions
    }

    class FakeRepository : FriendRepository {
        val listRequests = mutableListOf<Pair<Int, Int>>()
        val confirmations = mutableListOf<Triple<Long, Int, Permission?>>()
        var firstPage: List<FriendRequest?> = emptyList()
        var nextPage: List<FriendRequest?> = emptyList()
        var firstPageLimit = false
        var confirmResponse = ApiResponse(200, "success", data = ConfirmAdd())
        var networkFailure: Throwable? = null

        val friendInfoRequests = mutableListOf<Long>()
        var friendInfoResponse: ApiResponse<FriendInfo>? = null
        val deletions = mutableListOf<Long>()
        var deleteResponse = ApiResponse<Unit>(200, "success")
        val relationUpdates = mutableListOf<Pair<Long, Int>>()
        var relationResponse = ApiResponse<Unit>(200, "success")
        val remarkUpdates = mutableListOf<Pair<Long, String>>()
        var remarkResponse = ApiResponse<Unit>(200, "success")

        override suspend fun relationApplyList(page: Int, pageSize: Int): ApiResponse<FriendRequestList> {
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

        override suspend fun friendInfo(userId: Long): ApiResponse<FriendInfo> {
            friendInfoRequests += userId
            networkFailure?.let { throw it }
            return friendInfoResponse ?: ApiResponse(500, "no fixture")
        }

        override suspend fun deleteFriend(userId: Long): ApiResponse<Unit> {
            deletions += userId
            return deleteResponse
        }

        override suspend fun updateRelation(userId: Long, relation: Int): ApiResponse<Unit> {
            relationUpdates += userId to relation
            return relationResponse
        }

        override suspend fun updateRemark(userId: Long, remark: String): ApiResponse<Unit> {
            remarkUpdates += userId to remark
            return remarkResponse
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

    class Recorder : FriendFlowNavigation {
        val events = mutableListOf<String>()
        override fun openAddFriend(mode: Int) { events += "addFriend:$mode" }

        override fun openFriendRequestDetail(request: FriendRequest, friendLimit: Boolean) {
            events += "detail:${request.userId}:$friendLimit"
        }

        override fun openFriendInfo(userId: Long, type: Int, name: String?) { events += "info:$userId:$type:${name ?: '-'}" }
        override fun openChat(userId: Long) { events += "chat:$userId" }
        override fun openAgreeAdd() { events += "agreeAdd" }
        override fun showFriendNumLimitDialog() { events += "numLimitDialog" }
        override fun showPermissionSettings(friendUserId: Long) { events += "permissions:$friendUserId" }
    }
}
