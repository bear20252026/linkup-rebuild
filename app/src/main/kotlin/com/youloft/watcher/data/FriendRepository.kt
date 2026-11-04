package com.youloft.watcher.data

import android.app.Application
import android.content.Context
import com.youloft.linkup.api.dto.ConfirmAdd
import com.youloft.linkup.api.dto.Friend
import com.youloft.linkup.api.dto.FriendApply
import com.youloft.linkup.api.dto.FriendInfo
import com.youloft.linkup.api.dto.FriendRequestList
import com.youloft.linkup.api.dto.Permission
import com.youloft.linkup.api.dto.PublicKey
import com.youloft.linkup.api.dto.SearchFriend
import com.youloft.linkup.net.ApiResponse
import com.youloft.linkup.net.LinkUpApi
import com.youloft.linkup.net.LinkUpClient
import com.youloft.watcher.AndroidDeviceContext
import com.youloft.watcher.data.local.FriendCache
import com.youloft.watcher.data.local.InstallChannel
import com.youloft.watcher.data.local.FallbackInstallChannel
import com.youloft.watcher.data.local.KeyValueStores
import com.youloft.watcher.data.local.MmkvKeyValueStores
import com.youloft.watcher.data.local.AddFriendTipsPolicy
import com.youloft.watcher.data.local.AddFriendTips
import com.youloft.watcher.data.local.PermissionChannelGate
import com.youloft.watcher.data.local.PendingRemoteConfig
import com.youloft.watcher.data.local.RemoteConfig
import com.youloft.watcher.data.local.UserCache
import com.youloft.watcher.view.MatchCodeQrPainter
import com.youloft.watcher.view.QrCodeRenderer

/**
 * `repository/FriendRepository` in the recovered app: one class covers every relation endpoint,
 * so rebuilt screens take this interface rather than a per-screen one.
 */
interface FriendRepository {
    suspend fun relationApplyList(page: Int, pageSize: Int): ApiResponse<FriendRequestList>

    /** POST api/relation/confirm — the recovered body carries code, relation and the optional sens settings. */
    suspend fun confirmAdd(userId: Long, relation: Int, sensSetting: Permission?): ApiResponse<ConfirmAdd>

    suspend fun friendInfo(userId: Long): ApiResponse<FriendInfo>

    suspend fun deleteFriend(userId: Long): ApiResponse<Unit>

    suspend fun updateRelation(userId: Long, relation: Int): ApiResponse<Unit>

    suspend fun updateRemark(userId: Long, remark: String): ApiResponse<Unit>

    /** GET api/relation/pre_confirm — look a stranger up by match code before offering to add them. */
    suspend fun preConfirm(code: Long): ApiResponse<Friend>

    /** POST /api/relation/apply — the same three fields as confirm, but for someone not yet connected. */
    suspend fun applyAdd(code: Long, relation: Int, sensSetting: Permission?): ApiResponse<FriendApply>

    /** GET api/tool/rsa/public_key — the key the contact-search envelope is sealed under. */
    suspend fun serverPublicKey(): ApiResponse<PublicKey>

    /** POST /api/relation/match_by_phone — the body is the sealed envelope, never the phone numbers. */
    suspend fun matchByPhone(encrypted: String, keyEncrypted: String): ApiResponse<SearchFriend>
}

class NetworkFriendRepository(private val api: LinkUpApi) : FriendRepository {

    override suspend fun relationApplyList(page: Int, pageSize: Int) =
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

    override suspend fun friendInfo(userId: Long) = api.relationInfo(queryId = userId)

    override suspend fun deleteFriend(userId: Long) = api.relationDel(payload = mapOf("friendId" to userId))

    override suspend fun updateRelation(userId: Long, relation: Int) =
        api.relationUpdate(payload = mapOf("friendId" to userId, "relation" to relation))

    override suspend fun updateRemark(userId: Long, remark: String) =
        api.relationUpdateRemark(payload = mapOf("friendId" to userId, "remark" to remark))

    override suspend fun preConfirm(code: Long) = api.relationPreConfirm(code = code)

    override suspend fun applyAdd(code: Long, relation: Int, sensSetting: Permission?) =
        api.apiRelationApply(
            payload = mapOf(
                "code" to code,
                "relation" to relation,
                "sensSetting" to sensSetting,
            ),
        )

    override suspend fun serverPublicKey() = api.toolRsaPublicKey()

    override suspend fun matchByPhone(encrypted: String, keyEncrypted: String) =
        api.apiRelationMatchByPhone(payload = mapOf("encrypted" to encrypted, "keyEncrypted" to keyEncrypted))
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

    open val friends: FriendRepository by lazy {
        NetworkFriendRepository(LinkUpClient(context = deviceContext()).api)
    }

    open val userRepository: UserRepository by lazy {
        NetworkUserRepository(LinkUpClient(context = deviceContext()).api)
    }

    /** `manager/s2` + `manager/z0`: the single owner of the account record and its cache write. */
    open val users: UserManager by lazy { UserManager(userRepository, accountCache) }

    /** The cached account record, shared by the app graph readers and the user manager. */
    open val accountCache: UserCache by lazy { UserCache(keyValueStores) }

    private val friendCache by lazy { FriendCache(keyValueStores, accountCache) }

    private fun deviceContext() = AndroidDeviceContext(this, accountCache)

    /**
     * The live friend list and push channel the friend screens observe. The IM manager behind it is a
     * separate card, so the default keeps the shape of the contract without inventing push behaviour.
     */
    open val friendList: FriendListSource by lazy { PendingFriendListSource() }

    /**
     * Local key-value storage. Production is MMKV; tests inject the in-memory double so nothing here
     * needs the native library.
     */
    open val keyValueStores: KeyValueStores by lazy { MmkvKeyValueStores(this) }

    /** `hc.i` read the channel out of the APK's signing block; that reader is its own card. */
    open val installChannel: InstallChannel get() = FallbackInstallChannel()

    /** The vendor config SDK (`of.b.f("android-watcher", "156")`) is not reintegrated yet. */
    open val remoteConfig: RemoteConfig get() = PendingRemoteConfig

    private val permissionChannels by lazy { PermissionChannelGate(installChannel, remoteConfig) }

    /** `CacheUtils.isVip()` — cached account record, gates the friend-quota upsell. */
    open val isVip: Boolean get() = accountCache.isVip()

    /** `CacheUtils.friendList.size` — used to re-derive the quota after a confirmed add. */
    open val cachedFriendCount: Int get() = friendCache.cachedCount()

    /** `ConfigUtils.showPermissionChannel` matched against the install channel. */
    open val askPermissionsAfterAdd: Boolean get() = permissionChannels.asksAfterAddFriend()

    /** The channel-specific hint under the add-friend screen; null on channels that show none. */
    open val addFriendTips: AddFriendTips? get() = AddFriendTipsPolicy(installChannel).tips()

    /**
     * Draws the match-code QR. Recovered as zxing plus the app's own rounded-module painter; the
     * painter is rebuilt, so production draws for real and only tests opt out.
     */
    open val qrRenderer: QrCodeRenderer get() = MatchCodeQrPainter()
}

fun appGraph(context: Context): LinkUpApplication = context.applicationContext as LinkUpApplication
