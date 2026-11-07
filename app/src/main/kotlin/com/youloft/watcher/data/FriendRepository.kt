package com.youloft.watcher.data

import android.app.Application
import android.content.Context
import android.os.Build
import com.youloft.linkup.api.dto.ConfirmAdd
import com.youloft.linkup.api.dto.Friend
import com.youloft.linkup.api.dto.FriendApply
import com.youloft.linkup.api.dto.FriendInfo
import com.youloft.linkup.api.dto.FriendRequestList
import com.youloft.linkup.api.dto.Permission
import com.youloft.linkup.api.dto.PublicKey
import com.youloft.linkup.api.dto.SearchFriend
import com.youloft.linkup.api.dto.User
import com.youloft.linkup.net.ApiResponse
import com.youloft.linkup.net.LinkUpApi
import com.youloft.linkup.net.LinkUpClient
import com.youloft.linkup.location.LocationProviders
import com.youloft.linkup.location.canLocate
import com.youloft.linkup.location.provider.system.LocationPermissions
import com.youloft.linkup.map.TrackMapProviders
import com.youloft.linkup.map.provider.amap.AmapTrackMapProvider
import com.youloft.linkup.map.provider.baidu.BaiduTrackMapProvider
import com.youloft.watcher.AndroidDeviceContext
import com.youloft.watcher.data.local.FriendCache
import com.youloft.watcher.data.local.InstallChannel
import com.youloft.watcher.data.local.SigningBlockInstallChannel
import com.youloft.watcher.data.local.KeyValueStores
import com.youloft.watcher.data.local.LoginFlags
import com.youloft.watcher.data.local.MmkvKeyValueStores
import com.youloft.watcher.pages.login.OneTapAuth
import com.youloft.watcher.pages.login.UnavailableOneTapAuth
import com.youloft.watcher.data.local.AddFriendTipsPolicy
import com.youloft.watcher.data.local.AddFriendTips
import com.youloft.watcher.data.local.PermissionChannelGate
import com.youloft.watcher.data.local.PrivacyGate
import com.youloft.linkup.net.ConfigService
import com.youloft.watcher.data.local.StoredRemoteConfig
import com.youloft.watcher.data.local.RemoteConfig
import com.youloft.watcher.data.local.UserCache
import com.youloft.watcher.view.MatchCodeQrPainter
import com.youloft.watcher.view.QrCodeRenderer
import com.youloft.watcher.service.CollectController

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
            payload = mutableMapOf(
                "code" to userId,
                "relation" to relation,
                "sensSetting" to sensSetting,
            ),
        )

    override suspend fun friendInfo(userId: Long) = api.relationInfo(queryId = userId)

    override suspend fun deleteFriend(userId: Long) = api.relationDel(payload = mutableMapOf("friendId" to userId))

    override suspend fun updateRelation(userId: Long, relation: Int) =
        api.relationUpdate(payload = mutableMapOf("friendId" to userId, "relation" to relation))

    override suspend fun updateRemark(userId: Long, remark: String) =
        api.relationUpdateRemark(payload = mutableMapOf("friendId" to userId, "remark" to remark))

    override suspend fun preConfirm(code: Long) = api.relationPreConfirm(code = code)

    override suspend fun applyAdd(code: Long, relation: Int, sensSetting: Permission?) =
        api.apiRelationApply(
            payload = mutableMapOf(
                "code" to code,
                "relation" to relation,
                "sensSetting" to sensSetting,
            ),
        )

    override suspend fun serverPublicKey() = api.toolRsaPublicKey()

    override suspend fun matchByPhone(encrypted: String, keyEncrypted: String) =
        api.apiRelationMatchByPhone(payload = mutableMapOf("encrypted" to encrypted, "keyEncrypted" to keyEncrypted))
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

    /** `repository/c` — the login endpoints, plus the write that a successful one performs. */
    open val loginRepository: LoginRepository by lazy {
        NetworkLoginRepository(
            LinkUpClient(context = deviceContext()).api,
            users,
            LoginFlags(keyValueStores),
        )
    }

    /** The track screen's two reads: `api/location/days` and `api/location/v2/tracks`. */
    open val trackRepository: TrackRepository by lazy {
        NetworkTrackRepository(LinkUpClient(context = deviceContext()).api)
    }

    /** `CacheUtils.L()` / `Z0()` — the two account flags outside the user record. */
    open val loginFlags: LoginFlags get() = LoginFlags(keyValueStores)

    /** `CacheUtils.M0()` / `r1()` — the install-level privacy consent gate. */
    open val privacyGate: PrivacyGate get() = PrivacyGate(keyValueStores)

    /**
     * The carrier one-tap SDK (`com.g.gysdk`, `cn.fly.verify`). Neither jar can be taken out of the APK
     * and neither is on Maven Central, so production answers "no cached number" — which is the same
     * branch the recovered code took when the pre-login call failed, and it lands the user in the SMS sheet.
     */
    open val oneTapAuth: OneTapAuth get() = UnavailableOneTapAuth

    /** `manager/s2` + `manager/z0`: the single owner of the account record and its cache write. */
    open val users: UserManager by lazy { UserManager(userRepository, accountCache, accountSideEffects) }

    /** The cached account record, shared by the app graph readers and the user manager. */
    open val accountCache: UserCache by lazy { UserCache(keyValueStores) }

    private val friendCache by lazy { FriendCache(keyValueStores, accountCache) }

    private fun deviceContext() = AndroidDeviceContext(this, accountCache, installChannel)

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

    /**
     * `kc.i` (1.6.6; `hc.i` in 1.5.6) read the install channel out of this APK's own signing block
     * (`0x71777777`, JSON, `channel` key, fallback `"0"`), once per process. The reader is ported; our
     * builds have no such section until `gradle :net:tagChannel` runs, so this answers `0` and every
     * channel gate falls back.
     */
    open val installChannel: InstallChannel get() = SigningBlockInstallChannel.from(this)

    /**
     * `nf.b.f("android-watcher","166")` turned out to be **our own config service**, not a vendor SDK
     * (#17): a plain GET on `cfg.51wnl-cq.com`, which now 302s to `configsys.51wnl-cq.com`. Reads come
     * from the stored `Parameter_Map`, exactly like the recovered `ConfigUtils.j` did — in 1.6.6 that is
     * the only reader left, because `showPermissionChannel` lost its accessor along with the gate
     * (five-point sixteen).
     */
    open val remoteConfig: RemoteConfig get() = StoredRemoteConfig(keyValueStores)

    /**
     * The recovered client plus the identity `ConfigUtils$a.c()` handed to its interceptor: `bd` package
     * name, `cid` the constant, `av` version name, `chn` the channel with a `"-0"` suffix, `ov` the OS
     * release. Nothing required them — the same answer came back without — they steer targeting only.
     */
    open val configService: ConfigService
        get() = ConfigService(
            params = mutableMapOf(
                "bd" to packageName,
                "cid" to ConfigService.CLIENT_ID,
                "av" to deviceContext().appVersion,
                "chn" to installChannel.id() + "-0",
                "ov" to Build.VERSION.RELEASE,
            ),
        )

    private val permissionChannels by lazy { PermissionChannelGate(installChannel, remoteConfig) }

    /** `CacheUtils.isVip()` — cached account record, gates the friend-quota upsell. */
    open val isVip: Boolean get() = accountCache.isVip()

    /** `CacheUtils.friendList.size` — used to re-derive the quota after a confirmed add. */
    open val cachedFriendCount: Int get() = friendCache.cachedCount()

    /** `ConfigUtils.showPermissionChannel` matched against the install channel. */
    open val askPermissionsAfterAdd: Boolean get() = permissionChannels.asksAfterAddFriend()

    /** `datasource/c` — the device address book, read only after READ_CONTACTS has been granted. */
    open val contacts: ContactSource get() = ResolverContactSource(this)

    /** The channel-specific hint under the add-friend screen; null on channels that show none. */
    open val addFriendTips: AddFriendTips? get() = AddFriendTipsPolicy(installChannel).tips()

    /**
     * Draws the match-code QR. Recovered as zxing plus the app's own rounded-module painter; the
     * painter is rebuilt, so production draws for real and only tests opt out.
     */
    open val qrRenderer: QrCodeRenderer get() = MatchCodeQrPainter()

    /**
     * The vendor catalog for the location layer: Baidu first (what shipped), AMap second (its key is
     * empty until the third-party account is recovered, so it honestly removes itself), the framework
     * last as the only guaranteed source. Which one actually runs is [preferredProvider]'s call once the
     * [locationProviderSettings] preference is applied — see `data/LocationSources.kt`.
     */
    open val locationProviders: LocationProviders by lazy {
        locationProviders(context = this, privacyAgreed = { privacyGate.agreed() })
    }

    /** The `locationProviderId` override, read from the shared store so a debug screen can flip vendors. */
    open val locationProviderSettings: LocationProviderSettings
        get() = LocationProviderSettings(keyValueStores.shared)

    /**
     * The map catalog for the track page: Baidu first (the vendor that shipped), AMap second (proof the
     * seam takes a second vendor; no AAR today), and no framework fallback — the platform has no basemap,
     * so with no vendor installed the screen keeps its own "尚未接入" state instead of constructing one.
     */
    open val trackMaps: TrackMapProviders by lazy {
        TrackMapProviders(
            listOf(
                BaiduTrackMapProvider(privacyAgreed = { privacyGate.agreed() }),
                AmapTrackMapProvider(),
            ),
        )
    }

    /**
     * The guard collection switch: consent + permission + account, re-evaluated at every lifecycle
     * transition, driving [com.youloft.watcher.service.TrackCollectService].
     */
    open val collectController: CollectController by lazy {
        CollectController.forProduction(
            this,
            CollectController.Gates(
                privacyAgreed = { privacyGate.agreed() },
                canLocate = { LocationPermissions.of(this).state().canLocate() },
                signedIn = { users.current().userId > 0L },
            ),
        )
    }

    /**
     * What the recovered `LoginManager` did after storing an account. Only the collection trigger is
     * rebuilt so far: the account the user just signed in with is what makes the report route accept
     * fixes, so a login (or a sign-out leaving `userId == 0`) is a collection state change and the
     * controller re-evaluates it. IM bind and the rest stay with their own cards.
     */
    open val accountSideEffects: AccountSideEffects by lazy {
        val controller = collectController
        object : AccountSideEffects {
            override fun onVipStateChanged(user: User, wasVip: Boolean) = Unit

            override fun onSignedIn(user: User) {
                controller.refresh()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        collectController.attach(this)
    }
}

fun appGraph(context: Context): LinkUpApplication = context.applicationContext as LinkUpApplication
