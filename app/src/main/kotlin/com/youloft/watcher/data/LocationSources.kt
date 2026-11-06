package com.youloft.watcher.data

import android.content.Context
import com.youloft.linkup.location.LocationProviders
import com.youloft.linkup.location.LocationTuning
import com.youloft.linkup.location.provider.baidu.BaiduLocationProvider
import com.youloft.linkup.location.provider.system.SystemLocationProvider
import com.youloft.watcher.data.local.RemoteConfig

/**
 * The one place that decides which location SDK this build uses.
 *
 * Order is a fidelity decision, not a preference: Baidu first because it is what both shipped APKs ran
 * (`manager/x0.java:676-698`), framework second because it is the only source guaranteed to exist. When the
 * Baidu AAR is missing — which is today — [LocationProviders.preferred] simply returns the framework
 * provider, and the rest of the app never learns the difference.
 *
 * @param privacyAgreed gates the Baidu client. The recovered app only constructed `LocationClient` after the
 *   user accepted the privacy policy (`manager/SdkInitManager.java:279-281`), and the SDK throws if it is
 *   built without that agreement, so this has to come from the real consent flag rather than default true.
 */
fun locationProviders(
    context: Context,
    privacyAgreed: () -> Boolean = { false },
): LocationProviders = LocationProviders(
    listOf(
        BaiduLocationProvider(context = context, key = BAIDU_LOCATION_KEY, privacyAgreed = privacyAgreed),
        SystemLocationProvider(context),
    ),
)

/**
 * `LocationClient.setKey` argument from both APKs (`manager/x0.java:676`, `manager/s0.java`), i.e. the
 * Baidu AK that was already in the shipped binary.
 */
const val BAIDU_LOCATION_KEY = "ubL50e1vplPn3FsVVbDfQjAcgnU0eZ7P"

/**
 * The reporting thresholds as this build's config sees them. `manager/l.java:64-80` read every one of them
 * out of `ConfigUtils.d(key, default)` at construction time, so an absent key keeps the recovered default
 * rather than becoming zero — which for `locationMaxInterval` would have meant reporting every second.
 */
fun RemoteConfig.locationTuning(): LocationTuning = LocationTuning.fromConfig { number(it) }
