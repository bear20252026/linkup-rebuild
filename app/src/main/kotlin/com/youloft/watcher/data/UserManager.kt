package com.youloft.watcher.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.youloft.linkup.api.dto.User
import com.youloft.linkup.net.ApiResponse
import com.youloft.linkup.net.LinkUpApi
import com.youloft.watcher.data.local.UserCache
import com.youloft.watcher.data.local.isVip

/** `api/user/info`, the endpoint the recovered app called to re-pull the account record. */
interface UserRepository {
    suspend fun userInfo(): ApiResponse<User>
}

class NetworkUserRepository(private val api: LinkUpApi) : UserRepository {
    override suspend fun userInfo() = api.userInfo()
}

/**
 * The side effects the recovered code hung off a VIP-state transition (IM conversation refresh, the
 * home-screen widget, map mode reset, and a `LiveDataBus` publish). Every one of those subsystems is
 * its own card, so the transition is reported here instead of being silently dropped.
 */
interface AccountSideEffects {
    fun onVipStateChanged(user: User, wasVip: Boolean)

    /** What `LoginManager` did after storing an account: bind IM, `lf.b.b(userId)`, Umeng sign-in. */
    fun onSignedIn(user: User)

    /** The sign-out mirror: drop the IM session, stop the collection service, clear per-user state. */
    fun onSignedOut() {}
}

object NoAccountSideEffects : AccountSideEffects {
    override fun onVipStateChanged(user: User, wasVip: Boolean) = Unit
    override fun onSignedIn(user: User) = Unit
}

/**
 * `manager/s2` + `manager/z0` in the recovered app: one observable account record, one writer.
 *
 * Order matters and is preserved: read the old VIP state before the cache is overwritten, and store
 * the token that arrives inside the user record.
 */
class UserManager(
    private val repository: UserRepository,
    private val cache: UserCache,
    private val sideEffects: AccountSideEffects = NoAccountSideEffects,
) {

    private val _user = MutableLiveData(cache.user())
    val user: LiveData<User> = _user

    fun current(): User = cache.user()

    fun token(): String = cache.token()

    /**
     * The point a login response becomes local state — `manager/v0.h(user, …)` in the recovered code.
     *
     * Three recovered details are the reason this is not one line:
     *  - **the account record is written before the token** (`CacheUtils.i3` then `d3`), which is the
     *    order the original kept in `v0.h`;
     *  - a user is only written to `user2` when it is *usable as a session*: a login whose nickname is
     *    still empty, or a third-party login for an account that still has to complete its profile
     *    (`isRegister == true`), is **parked in a process-global holder instead** (`App.user`) — that
     *    holder is what the profile-completion screen reads;
     *  - the token is stored in both cases, so a parked user is signed in but not yet cached.
     *
     * `userId == 0` also stopped the desktop-widget update service and cleared the account LiveData in
     * the original; both belong to the widget/IM cards, so only the cache write is modelled here.
     */
    fun store(user: User, fromLogin: Boolean = true) {
        if (fromLogin && user.nickname.isNullOrEmpty() || !fromLogin && user.isRegister) {
            inFlight = user
        } else {
            cache.save(user)
            _user.value = user
        }
        cache.saveToken(user.token.orEmpty())
        // 会话本体（user2 JSON + token）到这里已写完；登录后的连锁动作（FGS/IM/设备自报）失败
        // 不得把会话带崩——各副作用内部有自己的兜底，这里再挡一层（登录不闪退，审计⑤）。
        runCatching { sideEffects.onSignedIn(user) }
    }

    /**
     * `App.f63249f` — the user a login returned but could not cache yet.
     * `App.b()`/`App.d(user)` read and write it; the profile-completion screen is its consumer.
     */
    var inFlight: User = User()
        private set

    /**
     * `manager/o2.r(user)` on its own — the write `repository/c` performs when a login only *binds* a
     * phone: replace the cached record and publish it, without touching the token or signing in.
     */
    fun saveRecord(user: User) {
        cache.save(user)
        _user.value = user
    }

    suspend fun refresh(sendVipState: Boolean = false): ApiResponse<User> {
        val response = repository.userInfo()
        val updated = response.data
        if (!response.isSuccess || updated == null) return response

        val wasVip = cache.isVip()
        if (wasVip != updated.isVip() || sendVipState) {
            sideEffects.onVipStateChanged(updated, wasVip)
        }
        cache.save(updated)
        _user.postValue(updated)
        return response
    }

    /**
     * Recovered sign-out: the token is set to the empty string and a default account record is
     * written back through the same key — not deleted.
     */
    fun signOut() {
        sideEffects.onSignedOut()
        cache.saveToken("")
        cache.save(User())
        _user.value = User()
    }
}
