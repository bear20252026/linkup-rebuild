package com.youloft.watcher.data.local

/**
 * Where the install channel comes from. The recovered app read it out of a custom section inside its
 * own APK signing block (magic id `1903654775`), parsed as JSON with a `channel` key, defaulting to
 * `"0"` when the section is absent. Our rebuilt APK does not carry such a section yet, so the
 * fallback is what runs today; the reader and the packaging step are separate units of work.
 */
interface InstallChannel {
    fun id(): String
}

class FallbackInstallChannel(private val id: String = DEFAULT) : InstallChannel {
    override fun id(): String = id

    companion object {
        const val DEFAULT = "0"
    }
}

/**
 * The vendor remote-config service the recovered `ConfigUtils` read through
 * (`of.b.f("android-watcher", "156")` → section `Parameter_Map`). That SDK is not in the rebuilt
 * dependency set yet, so this seam returns nothing until it is reintegrated.
 */
interface RemoteConfig {
    fun stringList(key: String): List<String>
}

object PendingRemoteConfig : RemoteConfig {
    override fun stringList(key: String): List<String> = emptyList()
}

/**
 * `ConfigUtils.showPermissionChannel` gate: ask for the permission settings after a friend is added
 * when this install's channel is listed — or when the list contains the wildcard the original
 * hard-coded as `-1`.
 */
class PermissionChannelGate(
    private val channel: InstallChannel,
    private val config: RemoteConfig,
) {

    fun asksAfterAddFriend(): Boolean = config.stringList(KEY).let { entries ->
        entries.any { it == channel.id() } || entries.any { it == WILDCARD }
    }

    companion object {
        const val KEY = "showPermissionChannel"
        const val WILDCARD = "-1"
    }
}
