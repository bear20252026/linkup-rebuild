package com.youloft.linkup.location

/**
 * One position sample, in the neutral currency the whole layer speaks.
 *
 * Field for field this is `com/youloft/watcher/bean/Location.java` minus the storage concerns (the Room
 * `id` and the `imPush` stay counter stay with the app that owns them), plus a datum tag the original
 * encoded implicitly by always converting Baidu's bd09ll to WGS84 at the SDK boundary
 * (`manager/x0.java:290`).
 *
 * Deliberately *not* here: `type` (the 0/2/3/4 arrival/departure tag the app stamps when it decides a fix
 * is worth reporting — see [LocationGateway]), and any vendor error code that survived into a fix.
 */
data class LocationFix(
    /** Always WGS84 by the time a provider hands it over; [LocationFix] constructors should use [normalized]. */
    val coord: Coord,
    /**
     * Wall-clock seconds, because the recovered app stamps `System.currentTimeMillis() / 1000` at callback
     * time and ignores the SDK's own fix time (`manager/x0.java:304`); the server treats `timePeriod` as
     * epoch seconds and buckets days with it.
     */
    val epochSeconds: Long,
    /** Radius of 68% confidence in metres — `BDLocation.getRadius()`, which is what the app stored as `accuracy`. */
    val accuracyMeters: Float,
    /**
     * Metres per second. Baidu reports km/h and only trusts it for a GNSS fix, so the original stored
     * `getSpeed() / 3.6` for locType 61 and **0.0f for every other fix type** (`manager/x0.java:288-289`) —
     * which the reporting cadence then reads as "stopped", so keep the same rule when adapting a vendor.
     */
    val speedMetersPerSecond: Float,
    /** Degrees clockwise from north, when the SDK reports it (`Location.getBearing`). */
    val bearingDegrees: Float? = null,
    /** Null when the provider was not asked for an address or could not produce one. */
    val place: Place? = null,
    /** Vendor's own classification of this fix (Baidu locType: 61 GNSS, 161 network). Reported as `locType`. */
    val vendorFixType: Int? = null,
    /** Which provider produced this, for logs and for the on-device vendor comparison. */
    val providerId: String,
) {
    companion object {
        /** Builds a fix from a raw vendor coordinate and the datum the vendor says it is in. */
        fun normalized(
            lat: Double,
            lng: Double,
            system: CoordSystem,
            epochSeconds: Long,
            accuracyMeters: Float,
            speedMetersPerSecond: Float,
            providerId: String,
            bearingDegrees: Float? = null,
            place: Place? = null,
            vendorFixType: Int? = null,
        ): LocationFix = LocationFix(
            coord = Coord(lat, lng, system).asWgs84(),
            epochSeconds = epochSeconds,
            accuracyMeters = accuracyMeters,
            speedMetersPerSecond = speedMetersPerSecond,
            bearingDegrees = bearingDegrees,
            place = place,
            vendorFixType = vendorFixType,
            providerId = providerId,
        )
    }

    /** A placeholder coordinate (0,0 / Double.MIN_VALUE) is what the original logged as `[定位]-无效` and dropped. */
    val isInvalid: Boolean
        get() = coord.isPlaceholder
}

/** Why a provider could not deliver a fix. */
enum class LocationFailure {
    /** Fine/coarse location permission missing — the caller has to ask, no SDK can fix this. */
    NO_PERMISSION,

    /** Location services off, or the SDK's provider disabled / network unreachable for its own servers. */
    SERVICE_UNAVAILABLE,

    /** A single-shot request gave up. `locationSettings.requestTimeWhenNoLoc` is the original's timeout knob. */
    TIMEOUT,

    /** The callback fired with an unusable payload — the `x0.java:284-287` branch, which the original only logged. */
    INVALID_FIX,

    /** The vendor SDK reported its own error (Baidu's negative locTypes, AMAP's errorCode). */
    VENDOR_ERROR,
}

/**
 * A failure that keeps the vendor's raw code beside it, because that number is the only thing an on-call
 * can grep and an enum cannot carry it.
 */
data class LocationFault(val reason: LocationFailure, val vendorCode: Int? = null, val detail: String? = null)

/** Callback a [LocationProvider] pushes to. Shaped after the SDKs' own listener pair. */
interface LocationListener {
    fun onFix(fix: LocationFix)

    fun onFailure(fault: LocationFault) {}
}
