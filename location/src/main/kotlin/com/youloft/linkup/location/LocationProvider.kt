package com.youloft.linkup.location

/**
 * The one location interface. Everything above it — the reporting loop, the track screen, the widgets —
 * is supposed to compile without knowing whether a fix came from Baidu, the framework, or a test double.
 *
 * ## Why the shape is this small
 *
 * The recovered shipping app had already found this seam on its own. `_raw/src166/sources/dg/a.java` holds
 * an immutable `Snapshot(locationMode, scanSpan)` with `update()` returning null when nothing changed, and
 * `manager/s0.java:764-785` calls `setLocOption` only when it did. Two fields vary; thirteen Baidu options
 * are constants. So [LocationRequest] carries the two that vary and the adapters own the rest — a vendor
 * that cannot express "battery saving" is not allowed to force the caller to special-case it.
 *
 * ## Implementing this for a new vendor
 *
 * 1. Read the datum off the SDK's fix rather than assuming it, then hand over WGS84 through
 *    [LocationFix.normalized] — a gcj02 vendor (AMAP, Tencent) plugged in unconverted would land ~500 m
 *    from where the app's own cached stay point says it is.
 * 2. Report `speedMetersPerSecond` in m/s and keep the original's "only trust speed on a GNSS fix" rule,
 *    which the reporting cadence depends on.
 * 3. Fill [capabilities] truthfully. `inlinePlace = false` is a legitimate answer and the caller then uses
 *    a [PlaceResolver]; pretending otherwise costs an address the user never sees.
 * 4. Decide whether [start] can be called repeatedly (framework yes, some SDKs need stop+start) and say so
 *    in [update]'s contract.
 */
interface LocationProvider {
    /** Stable, log-greppable identifier: `system`, `baidu`, `amap`, … */
    val id: String

    val capabilities: LocationCapabilities

    /**
     * False means the backing SDK is not in this build (no AAR, no key) — not that the user refused
     * permission. [LocationProviders] uses it to fall through to the next candidate; permission is a
     * separate question the UI answers via [permissionState].
     */
    val isInstalled: Boolean

    /**
     * Starts or re-points continuous sampling. Returns false when this provider cannot take the job at all
     * (not installed, no usable provider); transient problems are reported to [LocationListener.onFailure]
     * instead, because the caller still has to decide whether to fall back.
     */
    fun start(request: LocationRequest, listener: LocationListener): Boolean

    /**
     * Applies a new [LocationRequest] to a running provider. True if it changed something — the 1.6.6
     * dormancy switch (`manager/s0.java:810` -> Battery_Saving + 30 s) is worth a log line and a
     * re-registration, and false means the SDK was left alone.
     *
     * Must be callable while following; a provider that cannot do that restarts internally and still
     * returns true.
     */
    fun update(request: LocationRequest): Boolean

    /** Stops sampling but keeps the provider reusable ([start] again later). */
    fun stop()

    /**
     * One fix, as fast as the SDK can manage, without disturbing continuous sampling — what a screen that
     * just needs "where am I now" wants. Times out at [LocationRequest.singleFixTimeoutMillis]; the
     * original's equivalent was `Q()` "停留--网络变化--单次定位" plus `requestTimeWhenNoLoc`.
     */
    suspend fun singleFix(request: LocationRequest = LocationRequest.FOREGROUND): LocationResult

    /** The most recent fix this provider delivered, or null. The app cached this in MMKV (`CacheUtils.S1`). */
    fun lastFix(): LocationFix?

    /** Releases SDK handles. Implementations must also [stop]. */
    fun close()
}

/** Outcome of a one-shot request. */
sealed class LocationResult {
    data class Located(val fix: LocationFix) : LocationResult()
    data class Failed(val fault: LocationFault) : LocationResult()

    val fixOrNull: LocationFix?
        get() = (this as? Located)?.fix
}

/**
 * Location permission as the app has to present it: fine, coarse-only, or neither. Kept separate from
 * [LocationFailure.NO_PERMISSION] because the UI needs the first two to decide whether to ask again,
 * while the layer only cares that something is missing.
 */
enum class LocationPermission {
    GRANTED_FINE,
    GRANTED_COARSE_ONLY,
    DENIED,
}

/** Whether [LocationPermission.GRANTED_FINE] or coarse-only is enough for sampling to start. */
fun LocationPermission.canLocate(): Boolean = this != LocationPermission.DENIED
