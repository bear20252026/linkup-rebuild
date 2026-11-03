package com.youloft.watcher.pages.friend

import com.youloft.linkup.api.dto.Birthday
import com.youloft.linkup.api.dto.ConfirmAdd
import com.youloft.linkup.api.dto.FriendRequest
import com.youloft.linkup.api.dto.FriendRequestList
import com.youloft.linkup.api.dto.Permission
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.data.FriendRequestRepository
import com.youloft.watcher.data.LinkUpApplication

/**
 * Shared fixtures for the rebuilt friend-request flow. Robolectric builds the Application outside
 * the test instance, so the doubles it hands out live in one holder that both screens reset.
 */
object FriendFlowFixtures {
    var repository = FakeRepository()
    val flags = Flags()

    fun reset() {
        repository = FakeRepository()
        flags.vip = false
        flags.cachedFriends = 0
        flags.askPermissions = false
    }

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
}
