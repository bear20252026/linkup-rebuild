package com.youloft.watcher

import android.content.Context
import android.provider.Settings
import com.youloft.linkup.net.DeviceContext
import java.util.Locale
import java.util.TimeZone

/** Feeds the 11 common query parameters from the device, mirroring CacheUtils + v/v2/p in 1.6.6. */
class AndroidDeviceContext(private val context: Context) : DeviceContext {

    private val prefs = context.getSharedPreferences("linkup_recovery", Context.MODE_PRIVATE)

    override val timeZoneOffsetMinutes: Int
        get() = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    override val language: String get() = Locale.getDefault().language

    override val userId: Long get() = prefs.getLong("user_id", 0L)

    override val deviceId: String
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    override val channel: String get() = prefs.getString("channel", "official").orEmpty()

    override val appVersion: String
        get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    override val mainUser: Long get() = prefs.getLong("main_user", 0L)

    // ThinkingData ids: empty until the analytics SDK is re-integrated. The backend accepts them blank.
    override val distinctId: String get() = prefs.getString("distinct_id", "").orEmpty()
    override val shushuDeviceId: String get() = prefs.getString("shushu_device_id", "").orEmpty()

    override fun authToken(): String = prefs.getString("token", "").orEmpty()

    fun saveToken(token: String, userId: Long) {
        prefs.edit().putString("token", token).putLong("user_id", userId).apply()
    }
}
