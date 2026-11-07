package com.youloft.watcher.data.local

/**
 * The two account-level flags `CacheUtils` kept outside the user record.
 *
 * `lastLoginType` lives in the default MMKV instance (`CacheUtils.L()` / `j2(int)`), which is why the
 * login screen can answer "上次登录" before any account data is read. The notification flag lives in the
 * **per-user** instance (`CacheUtils.Z0()` / `G2(true)`, i.e. the same instance that holds `friendList`),
 * so it is asked once per account rather than once per install.
 */
class LoginFlags(private val stores: KeyValueStores) {

    /** `CacheUtils.L()` — 0 when nobody has logged in on this install yet. */
    fun lastLoginType(): Int = stores.shared.int(KEY_LAST_LOGIN_TYPE)

    fun recordLastLoginType(type: Int) {
        stores.shared.putInt(KEY_LAST_LOGIN_TYPE, type)
    }

    /** `CacheUtils.Z0()`, the "don't ask for POST_NOTIFICATIONS twice" marker, per account. */
    fun notificationRequested(userId: Long): Boolean =
        stores.forUser(userId).boolean(KEY_REQUESTED_NOTIFICATION)

    fun markNotificationRequested(userId: Long) {
        stores.forUser(userId).putBoolean(KEY_REQUESTED_NOTIFICATION, true)
    }

    /** `CacheUtils.v()` — the guide has been seen up to this version; the shipped sentinel was 4. */
    fun guideSeenVersion(): Int = stores.shared.int(KEY_GUIDE_SEEN_VERSION)

    /** `CacheUtils.H1(version)` — written by the guide's skip/finish. */
    fun markGuideSeen(version: Int) {
        stores.shared.putInt(KEY_GUIDE_SEEN_VERSION, version)
    }

    companion object {
        const val KEY_LAST_LOGIN_TYPE = "lastLoginType"
        const val KEY_REQUESTED_NOTIFICATION = "isRequestedNotification"
        const val KEY_GUIDE_SEEN_VERSION = "guideSeenVersion"

        /** The `platform` / `typeId` numbers the recovered app wrote into `lastLoginType`. */
        const val PLATFORM_WECHAT = 4
        const val PLATFORM_QQ = 5
        const val LOGIN_TYPE_PHONE = 6
    }
}
