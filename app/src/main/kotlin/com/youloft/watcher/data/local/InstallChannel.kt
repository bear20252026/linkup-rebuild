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
 * `ConfigUtils.showPermissionChannel` matched against the install channel.
 *
 * Both rules are recovered facts, one per shipped version: 1.5.6 asked its vendor config service for
 * a list of channels, 1.6.6 dropped that and compares the channel against `3301` in code — at all
 * four call sites (add friend, contacts, request list, request detail). The rebuilt app is the 1.6.6
 * build, so [asksAfterAddFriend] is the shipping rule and [asksAfterAddFriendByConfig] is kept as
 * the older one because it explains what the config key was for.
 */
class PermissionChannelGate(
    private val channel: InstallChannel,
    private val config: RemoteConfig,
) {

    fun asksAfterAddFriend(): Boolean = channel.id() == PERMISSION_CONSENT_CHANNEL

    fun asksAfterAddFriendByConfig(): Boolean = config.stringList(KEY).let { entries ->
        entries.any { it == channel.id() } || entries.any { it == WILDCARD }
    }

    companion object {
        const val KEY = "showPermissionChannel"
        const val WILDCARD = "-1"
        const val PERMISSION_CONSENT_CHANNEL = "3301"
    }
}

/**
 * The hint the add-friend screen shows under its cards. `com.youloft.watcher.utils.p` compares the
 * channel id against four store builds; the wording differs per store because each one is a different
 * promise about who has to install what.
 */
class AddFriendTipsPolicy(private val channel: InstallChannel) {

    fun tips(): AddFriendTips? = when (channel.id()) {
        CHANNEL_LOCATION_CONSENT -> AddFriendTips.LocationMutualConsent
        PermissionChannelGate.PERMISSION_CONSENT_CHANNEL -> AddFriendTips.BothInstallOrVip
        CHANNEL_BOTH_INSTALL_A, CHANNEL_BOTH_INSTALL_B -> AddFriendTips.BothInstallApp
        else -> null
    }

    companion object {
        /** Also the channel whose "not relatives" lock screen is hidden (`helper/d`). */
        const val CHANNEL_LOCATION_CONSENT = "3801"
        const val CHANNEL_BOTH_INSTALL_A = "3300"
        const val CHANNEL_BOTH_INSTALL_B = "3302"
    }
}

/** Only channel 3301 has a translated string; the other two wordings are hardcoded in the original. */
enum class AddFriendTips {
    LocationMutualConsent,
    BothInstallOrVip,
    BothInstallApp,
}
