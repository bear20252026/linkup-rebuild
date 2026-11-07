package com.youloft.watcher.data

import android.content.Context
import com.youloft.linkup.location.LocationProvider
import com.youloft.linkup.location.LocationProviders
import com.youloft.linkup.location.LocationTuning
import com.youloft.linkup.location.provider.amap.AmapLocationProvider
import com.youloft.linkup.location.provider.baidu.BaiduLocationProvider
import com.youloft.linkup.location.provider.system.SystemLocationProvider
import com.youloft.watcher.data.local.KeyValueStore
import com.youloft.watcher.data.local.RemoteConfig

/**
 * The one place that decides which location SDK this build uses, and the one place allowed to name them.
 *
 * The candidate chain is the whole vendor list the layer knows how to bind; each candidate answers for
 * itself whether it can actually run (`isInstalled` = AAR present + key configured + privacy agreed), and
 * [LocationProviders.preferred] takes the first that can. Order is a fidelity decision, not a preference:
 * Baidu first because it is what both shipped APKs ran (`manager/x0.java:676-698`), AMap second as the
 * proof that a second vendor slots in without touching anything above the interface (its key is empty
 * today, so it honestly removes itself), framework last because it is the only source guaranteed to
 * exist.
 *
 * Which candidate wins is no longer hardwired to that order: [preferredProvider] applies the
 * `locationProviderId` setting on top (see [LocationProviderSettings]), so a build, a debug screen or a
 * future remote-config push can pin any vendor by id — and a pinned vendor that cannot run falls back to
 * the chain rather than taking the app down with it.
 *
 * @param privacyAgreed gates the vendor clients. The recovered app only constructed `LocationClient`
 *   after the user accepted the privacy policy (`manager/SdkInitManager.java:279-281`), and the SDKs throw
 *   if built without that agreement, so this has to come from the real consent flag rather than default
 *   true.
 */
fun locationProviders(
    context: Context,
    privacyAgreed: () -> Boolean = { false },
): LocationProviders = LocationProviders(
    listOf(
        BaiduLocationProvider(context = context, key = BAIDU_LOCATION_KEY, privacyAgreed = privacyAgreed),
        AmapLocationProvider(context = context, key = AMAP_LOCATION_KEY, privacyAgreed = privacyAgreed),
        SystemLocationProvider(context),
    ),
)

/**
 * Applies the provider preference to the candidate chain: a configured id wins **only if that provider can
 * actually run in this build**, otherwise the chain's own order decides. A settings key naming a provider
 * that is missing its AAR must degrade, not break — that is what makes the setting safe to ship.
 */
fun LocationProviders.preferredProvider(preferredId: String?): LocationProvider? =
    preferredId?.let { id -> find(id)?.takeIf { it.isInstalled } } ?: preferred()

/**
 * `LocationClient.setKey` argument from both APKs (`manager/x0.java:676`, `manager/s0.java`), i.e. the
 * Baidu AK that was already in the shipped binary.
 */
const val BAIDU_LOCATION_KEY = "ubL50e1vplPn3FsVVbDfQjAcgnU0eZ7P"

/**
 * The AMap AK. Deliberately empty: the third-party accounts were lost with the source tree, and an empty
 * key is what keeps [AmapLocationProvider] out of the installed chain honestly. When the account is
 * recovered this becomes a real value and the provider switches on — that is the whole swap, no code
 * above the interface changes.
 */
const val AMAP_LOCATION_KEY = ""

/**
 * Which location provider to use, stored in the shared MMKV store so a debug screen or a future config
 * push can flip vendors without a rebuild. Null/blank means "no preference": the candidate chain's own
 * order decides.
 */
class LocationProviderSettings(private val store: KeyValueStore) {
    val preferredId: String?
        get() = store.string(KEY)?.takeIf { it.isNotBlank() }

    fun setPreferred(id: String?) {
        if (id == null) store.putString(KEY, "") else store.putString(KEY, id)
    }

    companion object {
        const val KEY = "locationProviderId"
    }
}

/**
 * The reporting thresholds as this build's config sees them. `manager/l.java:64-80` read every one of them
 * out of `ConfigUtils.d(key, default)` at construction time, so an absent key keeps the recovered default
 * rather than becoming zero — which for `locationMaxInterval` would have meant reporting every second.
 */
fun RemoteConfig.locationTuning(): LocationTuning = LocationTuning.fromConfig { number(it) }
