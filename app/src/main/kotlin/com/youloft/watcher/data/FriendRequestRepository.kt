package com.youloft.watcher.data

import android.app.Application
import android.content.Context
import com.youloft.linkup.api.dto.ConfirmAdd
import com.youloft.linkup.api.dto.FriendRequestList
import com.youloft.linkup.api.dto.Permission
import com.youloft.linkup.net.ApiResponse
import com.youloft.linkup.net.LinkUpApi
import com.youloft.linkup.net.LinkUpClient
import com.youloft.watcher.AndroidDeviceContext

/** The relation endpoints this screen needs, kept small enough to fake in tests. */
interface FriendRequestRepository {
    suspend fun applyList(page: Int, pageSize: Int): ApiResponse<FriendRequestList>

    suspend fun confirmAdd(userId: Long, relation: Int, sensSetting: Permission?): ApiResponse<ConfirmAdd>
}

class NetworkFriendRequestRepository(private val api: LinkUpApi) : FriendRequestRepository {

    override suspend fun applyList(page: Int, pageSize: Int) =
        api.apiRelationApplyList(page = page, pageSize = pageSize)

    override suspend fun confirmAdd(userId: Long, relation: Int, sensSetting: Permission?) =
        api.relationConfirm(
            code = userId,
            payload = mapOf(
                "code" to userId,
                "relation" to relation,
                "sensSetting" to sensSetting,
            ),
        )
}

/**
 * Construction of dependencies. The original used Hilt; this hand-written graph exists so screens
 * can already take interfaces through their constructor, and Hilt replaces this class later
 * without touching the screens.
 *
 * The three flags below are reads of the `CacheUtils` / `ConfigUtils` MMKV layer, which is a
 * separate card in the recovery backlog. They are open so a screen can be driven and tested now
 * instead of being silently hardcoded to one branch.
 */
open class LinkUpApplication : Application() {

    open val friendRequests: FriendRequestRepository by lazy {
        NetworkFriendRequestRepository(LinkUpClient(context = AndroidDeviceContext(this)).api)
    }

    /** `CacheUtils.isVip()` — gates the friend-quota upsell. */
    open val isVip: Boolean get() = false

    /**
     * The live friend list and push channel the friend screens observe. The IM manager behind it is a
     * separate card, so the default keeps the shape of the contract without inventing push behaviour.
     */
    open val friendList: FriendListSource by lazy { PendingFriendListSource() }

    /** `CacheUtils.friendList.size` — used to re-derive the quota after a confirmed add. */
    open val cachedFriendCount: Int get() = 0

    /** `ConfigUtils.showPermissionChannel` matched against the install channel. */
    open val askPermissionsAfterAdd: Boolean get() = false
}

fun appGraph(context: Context): LinkUpApplication = context.applicationContext as LinkUpApplication
