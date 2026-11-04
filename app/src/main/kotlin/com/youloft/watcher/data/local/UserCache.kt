package com.youloft.watcher.data.local

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.youloft.linkup.api.dto.FriendList
import com.youloft.linkup.api.dto.User

/**
 * The cached account record, read exactly where the recovered `CacheUtils.y0()` read it: the key
 * `user2` in the default store, decoded with Gson.
 *
 * The original also fell back to an old `Parcelable` blob under a numeric key, for users upgrading
 * from a much older build. That path is not ported (our DTOs are not Parcelable), so a returning
 * user of that vintage simply looks logged out until the login flow — which owns the writer — lands.
 */
class UserCache(
    private val stores: KeyValueStores,
    private val gson: Gson = Gson(),
) {

    fun user(): User = stores.shared.string(KEY_USER)?.let { raw ->
        runCatching { gson.fromJson(raw, User::class.java) }.getOrNull()
    } ?: User()

    fun isVip(): Boolean = user().isVip()

    /** `CacheUtils.P2` / `U2`: the account JSON and the token both live in the default store. */
    fun token(): String = stores.shared.string(KEY_TOKEN).orEmpty()

    fun saveToken(token: String) {
        stores.shared.putString(KEY_TOKEN, token)
    }

    /**
     * `CacheUtils.U2` — writes the whole record as JSON under `user2`. Logout writes a default
     * `User()` through this same path rather than clearing the key, which is what the recovered
     * sign-out did, so the key is always present once a device has ever logged in.
     */
    fun save(user: User) {
        stores.shared.putString(KEY_USER, gson.toJson(user))
    }

    /** `CacheUtils.z0()` — the id the per-user store is named after. */
    fun userId(): Long = user().userId

    companion object {
        const val KEY_USER = "user2"
        const val KEY_TOKEN = "token"
    }
}

/**
 * `User.isVip()` as recovered: own subscription first, then any shared-VIP grant that is still of a
 * positive subtype.
 */
internal fun User.isVip(): Boolean =
    vipSubType > 0 || shareToMeList.orEmpty().any { (it?.vipSubType ?: 0) > 0 }

/**
 * The cached friend list, read from the per-user store under `friendList` — the same split the
 * recovered code used (account JSON in the shared store, the list in the user-named one).
 */
class FriendCache(
    private val stores: KeyValueStores,
    private val users: UserCache,
    private val gson: Gson = Gson(),
) {

    fun cachedFriends(): List<FriendList.Info> {
        val raw = stores.forUser(users.userId()).string(KEY_FRIENDS) ?: return emptyList()
        val type = object : TypeToken<List<FriendList.Info?>>() {}.type
        return runCatching { gson.fromJson<List<FriendList.Info?>>(raw, type) }
            .getOrNull()
            ?.filterNotNull()
            ?: emptyList()
    }

    /** The original counted a missing cache as zero, then subtracted the local account itself. */
    fun cachedCount(): Int = cachedFriends().size

    companion object {
        const val KEY_FRIENDS = "friendList"
    }
}
