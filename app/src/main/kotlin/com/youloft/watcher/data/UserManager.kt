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
}

object NoAccountSideEffects : AccountSideEffects {
    override fun onVipStateChanged(user: User, wasVip: Boolean) = Unit
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

    /** `z0.g(user, ...)` — the point a login response is turned into local state. */
    fun store(user: User) {
        cache.saveToken(user.token.orEmpty())
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
        cache.saveToken("")
        cache.save(User())
        _user.value = User()
    }
}
