package com.youloft.watcher

import android.content.Context
import android.provider.Settings
import com.youloft.linkup.net.DeviceContext
import com.youloft.watcher.data.local.InstallChannel
import com.youloft.watcher.data.local.UserCache
import java.util.Locale
import java.util.TimeZone

/**
 * Feeds the 11 common query parameters plus the JWT. The account fields (token, userId, mainUser)
 * come from the recovered MMKV location — `user2` and `token` in the default instance — so any screen
 * that runs after a login signs its requests with the same identity the cache holds.
 *
 * `channel` reads the same signing-block section the UI gates read (via the app graph), so a request is
 * signed with the channel the install actually carries; our own builds carry none and answer `0`.
 */
class AndroidDeviceContext(
    private val context: Context,
    private val accounts: UserCache? = null,
    private val channels: InstallChannel? = null,
) : DeviceContext {

    private val prefs = context.getSharedPreferences("linkup_recovery", Context.MODE_PRIVATE)

    override val timeZoneOffsetMinutes: Int
        get() = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    override val language: String get() = Locale.getDefault().language

    override val userId: Long get() = accounts?.userId() ?: prefs.getLong("user_id", 0L)

    override val deviceId: String
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    /** One source of truth with the channel-gated UI rules — see `LinkUpApplication.installChannel`. */
    override val channel: String get() = channels?.id() ?: prefs.getString("channel", "official").orEmpty()

    override val appVersion: String
        get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    override val mainUser: Long get() = accounts?.user()?.mainUser ?: prefs.getLong("main_user", 0L)

    // ThinkingData ids: empty until the analytics SDK is re-integrated. The backend accepts them blank.
    override val distinctId: String get() = prefs.getString("distinct_id", "").orEmpty()
    override val shushuDeviceId: String get() = prefs.getString("shushu_device_id", "").orEmpty()

    override fun authToken(): String =
        accounts?.token()?.takeIf { it.isNotEmpty() } ?: prefs.getString("token", "").orEmpty()
}
