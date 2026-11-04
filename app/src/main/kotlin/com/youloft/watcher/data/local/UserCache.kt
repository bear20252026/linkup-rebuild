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

    /** `CacheUtils.z0()` — the id the per-user store is named after. */
    fun userId(): Long = user().userId

    companion object {
        const val KEY_USER = "user2"
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
