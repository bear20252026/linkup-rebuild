package com.youloft.linkup.location

/**
 * What a caller asks a [LocationProvider] to do — the neutral restatement of the two things the recovered
 * 1.6.6 app actually varied at runtime.
 *
 * `_raw/src166/sources/dg/a.java:56-121` is that version's whole configuration surface: an immutable
 * `Snapshot(locationMode, scanSpan)` whose `update()` returns **null when nothing changed**, and whose
 * `toOption()` hard-codes the remaining thirteen Baidu fields. `manager/s0.java:764-785` then calls
 * `setLocOption` only if the snapshot changed. So the original already treated "mode + interval" as the
 * only portable knobs, which is what this type models; the vendor-specific thirteen stay inside the
 * adapters.
 */
data class LocationRequest(
    /** Foreground sampling wants ~2 s; the original drops to `locationScanMillsWhenDormancy` when idle. */
    val intervalMillis: Long = 2_000,
    val accuracy: LocationAccuracy = LocationAccuracy.HIGH,
    /**
     * Ask the SDK to resolve an address with the fix. Baidu charges nothing extra for it and the original
     * always asked (`setIsNeedAddress(true)`); the framework has no equivalent, so its provider answers
     * `false` here in [LocationCapabilities] and the caller uses a [PlaceResolver] instead.
     */
    val needsPlace: Boolean = true,
    /** Whether to also ask for the nearby-POI list that fills `Place.poiName`. */
    val needsPoi: Boolean = true,
    /** `disableLocCache = true` in both shipped versions: a stale cached fix must never look like movement. */
    val allowCachedFixes: Boolean = false,
    /** Timeout for [LocationProvider.singleFix]; the original's own knob was `requestTimeWhenNoLoc` (600 s). */
    val singleFixTimeoutMillis: Long = 30_000,
) {
    companion object {
        /** The app's foreground state: high accuracy, 2 s, with address. */
        val FOREGROUND = LocationRequest()

        /** `s0.java:810` — Battery_Saving plus the dormancy interval, still with an address. */
        fun dormant(intervalMillis: Long) = LocationRequest(
            intervalMillis = intervalMillis,
            accuracy = LocationAccuracy.BATTERY_SAVING,
        )
    }
}

/** The two accuracy levels the recovered app used. AMAP/Tencent/Google map onto these without inventing more. */
enum class LocationAccuracy {
    /** `LocationClientOption.LocationMode.Hight_Accuracy` — GNSS + network fused. */
    HIGH,

    /** `LocationClientOption.LocationMode.Battery_Saving` — network only, used while dormant. */
    BATTERY_SAVING,
}

/** What a provider can and cannot do, so callers can degrade instead of assuming Baidu. */
data class LocationCapabilities(
    /** Datum the SDK reports before this layer normalizes it — never trusted, always read from the SDK. */
    val nativeCoordSystem: CoordSystem,
    /** Can the SDK attach an address to the fix itself ([LocationRequest.needsPlace])? */
    val inlinePlace: Boolean,
    /** Can it name a nearby POI? The framework `Geocoder` cannot. */
    val poiList: Boolean,
    /** Does it need an app key plus a privacy-agreement call before it will produce anything? */
    val needsVendorKey: Boolean,
    /** Can the interval change while running, or does that require stop+start? */
    val supportsRuntimeInterval: Boolean,
)
