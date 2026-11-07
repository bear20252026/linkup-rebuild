package com.youloft.linkup.location.provider.amap

import android.content.Context
import com.youloft.linkup.location.CoordSystem
import com.youloft.linkup.location.LocationCapabilities
import com.youloft.linkup.location.LocationFault
import com.youloft.linkup.location.LocationFailure
import com.youloft.linkup.location.LocationFix
import com.youloft.linkup.location.LocationListener
import com.youloft.linkup.location.LocationProvider
import com.youloft.linkup.location.LocationRequest
import com.youloft.linkup.location.LocationResult
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * The AMap location adapter — the second vendor behind [com.youloft.linkup.location.LocationProvider], and
 * the proof that the seam takes one: it lands in `:location` without a single change above the interface,
 * reaches its SDK through [AmapSdk]'s reflection with no AAR on the classpath, and hands over WGS84 via
 * [LocationFix.normalized] exactly like Baidu and the framework do.
 *
 * **Fidelity statement, so nobody cites this file as recovered behaviour.** The shipping app never carried
 * AMap — there is no decompiled ground truth to transcribe, and nothing here pretends otherwise. The
 * bindings follow the official AMap location SDK API; the option block in [AmapSdk.buildOption] is this
 * project's minimal choice (mode / interval / address / once), not a recovered constant; and the vendor key
 * is empty because the third-party accounts were lost with the source tree — see [COMPANION_KEY_NOTE] in
 * the companion. The doubles under `location/src/test/java/com/amap/api/location` carry the same
 * signatures, which is what makes this code path executable offline.
 *
 * Vendor facts this adapter is written against, each pinned by a test:
 *
 * - the callback fires for failures too — `AMapLocation.getErrorCode() != 0` is the failure, and it is
 *   gated before any coordinate is read;
 * - `getSpeed()` is inherited from `android.location.Location` and is **already m/s** — Baidu's
 *   `locType == 61` km/h division has no counterpart, and re-dividing would make the reporting cadence
 *   3.6× stingier;
 * - the datum is read off the fix (`getCoordType()` → `GCJ02`) and normalized here, so a fix stored next to
 *   a Baidu-sourced one sits where it should;
 * - the client needs the two-step privacy call before its constructor is legal, which is why the privacy
 *   flag counts against [isInstalled] rather than surfacing as a first-start crash.
 *
 * @param key the vendor application key. Empty (today) keeps the provider out of the installed chain
 *   honestly; a real key flips it on without touching anything else.
 */
class AmapLocationProvider(
    private val context: Context,
    private val key: String,
    private val privacyAgreed: () -> Boolean = { true },
    private val sdk: AmapSdk = AmapSdk(),
) : LocationProvider {
    private var client: Any? = null

    /** The dynamic proxy handed to the SDK: bound once, and dispatches to whoever is listening now. */
    private var proxy: Any? = null
    private var stream: LocationListener? = null

    /** Set only by [singleFix]; the SDK has no per-request callback, so the one delivery is claimed here. */
    private var oneShot: Waiting? = null
    private var request = LocationRequest.FOREGROUND
    private var last: LocationFix? = null

    override val id = "amap"

    override val capabilities = LocationCapabilities(
        nativeCoordSystem = CoordSystem.GCJ02,
        // setNeedAddress(true) fills province/city/district/street/streetNum and poiName on the fix itself.
        inlinePlace = true,
        // AMap has a single poiName string, not Baidu's POI list.
        poiList = false,
        needsVendorKey = true,
        // setLocationOption on a live client is the documented way to re-point it (dormancy swap).
        supportsRuntimeInterval = true,
    )

    /**
     * Three gates, each falsifiable: the AAR on the classpath, a key to authorize it, and the privacy
     * agreement the SDK demands before its constructor is legal. Any one missing reports "not installed" so
     * [com.youloft.linkup.location.LocationProviders] falls through instead of starting a client that
     * throws.
     */
    override val isInstalled: Boolean
        get() = sdk.isPresent && key.isNotBlank() && privacyAgreed()

    override fun start(request: LocationRequest, listener: LocationListener): Boolean {
        val client = prepare(listener) ?: return false
        stream = listener
        this.request = request
        val option = sdk.buildOption(request) ?: return false
        if (!sdk.setLocationOption(client, option)) return false
        return sdk.start(client)
    }

    override fun update(request: LocationRequest): Boolean {
        val client = client ?: return false
        if (request == this.request) return false
        val option = sdk.buildOption(request) ?: return false
        this.request = request
        return sdk.setLocationOption(client, option)
    }

    override fun stop() {
        client?.let { sdk.stop(it) }
    }

    /**
     * One fix via the SDK's own `setOnceLocation(true)` + `startLocation()` path. Unlike Baidu there is no
     * request-only call to lean on, so a [singleFix] while following re-points the client to one-shot;
     * whoever is following must call [start] again afterwards. Stated here because the original's
     * `manual_request` caveat (`s0.java:3031`) was Baidu-specific and must not be copied as if it were
     * AMap's.
     */
    override suspend fun singleFix(request: LocationRequest): LocationResult {
        val client = prepare(noopListener) ?: return LocationResult.Failed(
            LocationFault(LocationFailure.SERVICE_UNAVAILABLE, detail = "高德定位不可用: ${sdk.missingReason}"),
        )
        this.request = request
        val option = sdk.buildOption(request, once = true) ?: return LocationResult.Failed(
            LocationFault(LocationFailure.SERVICE_UNAVAILABLE, detail = "无法构造定位参数"),
        )
        if (!sdk.setLocationOption(client, option)) {
            return LocationResult.Failed(LocationFault(LocationFailure.VENDOR_ERROR, detail = "setLocationOption 不可用"))
        }
        return try {
            withTimeout(request.singleFixTimeoutMillis) {
                awaitOneShot(client)
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            // The timeout cancels the coroutine, whose cancellation handler already dropped our claim.
            LocationResult.Failed(
                LocationFault(LocationFailure.TIMEOUT, detail = "${request.singleFixTimeoutMillis}ms 内没有定位结果"),
            )
        }
    }

    override fun lastFix(): LocationFix? = last

    /**
     * Stop, release the SDK's own handles (`onDestroy`), and drop. One client per process, like the other
     * adapters — the proxy stays bound for the life of that client.
     */
    override fun close() {
        stop()
        client?.let { sdk.destroy(it) }
        proxy = null
        client = null
        stream = null
        oneShot = null
    }

    private suspend fun awaitOneShot(client: Any): LocationResult = suspendCancellableCoroutine { continuation ->
        // Kept as an object rather than a bare lambda so two singleFix calls cannot steal each other's answer.
        val waiting = Waiting { result ->
            if (continuation.isActive) continuation.resume(result)
        }
        oneShot = waiting
        // AMap's startLocation is void: acceptance is "found and did not throw", already checked by
        // sdk.start — so unlike Baidu there is no rejection code to short-circuit on, and the caller's
        // timeout is the only backstop for a client that never calls back.
        val accepted = sdk.start(client)
        if (!accepted) {
            if (oneShot === waiting) oneShot = null
            continuation.resume(
                LocationResult.Failed(
                    LocationFault(LocationFailure.VENDOR_ERROR, detail = "startLocation 不可用"),
                ),
            )
        }
        continuation.invokeOnCancellation { if (oneShot === waiting) oneShot = null }
    }

    /** Creates the client and installs the proxy listener; reports why it could not, and returns null. */
    private fun prepare(listener: LocationListener): Any? {
        if (!sdk.isPresent) {
            listener.onFailure(LocationFault(LocationFailure.SERVICE_UNAVAILABLE, detail = sdk.missingReason))
            return null
        }
        if (key.isBlank()) {
            listener.onFailure(
                LocationFault(LocationFailure.SERVICE_UNAVAILABLE, detail = "没有高德 key：三方账号随源码一起丢了"),
            )
            return null
        }
        if (!privacyAgreed()) {
            listener.onFailure(
                LocationFault(
                    LocationFailure.SERVICE_UNAVAILABLE,
                    detail = "未同意隐私政策：AMap 客户端构造前必须走完两步隐私确认",
                ),
            )
            return null
        }
        if (!sdk.agreePrivacy(context)) {
            listener.onFailure(LocationFault(LocationFailure.VENDOR_ERROR, detail = sdk.missingReason))
            return null
        }
        if (!sdk.setKey(key)) {
            listener.onFailure(LocationFault(LocationFailure.VENDOR_ERROR, detail = "setApiKey 不可用"))
            return null
        }
        client?.let { return it }
        val created = sdk.createClient(context) ?: run {
            listener.onFailure(LocationFault(LocationFailure.VENDOR_ERROR, detail = sdk.missingReason))
            return null
        }
        client = created
        val handler = InvocationHandler { _, method: Method, args: Array<Any?>? ->
            if (method.name == "onLocationChanged") {
                // Wall clock at callback time, same stamping rule as the other adapters (x0.java:304).
                dispatch(sdk.readFix(args?.firstOrNull(), id, System.currentTimeMillis() / 1000))
            }
            null
        }
        val listenerProxy = sdk.makeListener(handler) ?: run {
            listener.onFailure(LocationFault(LocationFailure.VENDOR_ERROR, detail = sdk.missingReason))
            return null
        }
        proxy = listenerProxy
        if (!sdk.setLocationListener(created, listenerProxy)) {
            listener.onFailure(LocationFault(LocationFailure.VENDOR_ERROR, detail = "setLocationListener 不可用"))
            client = null
            proxy = null
            return null
        }
        return created
    }

    private fun dispatch(outcome: AmapFix) {
        val pending = oneShot
        oneShot = null
        when (outcome) {
            is AmapFix.Ready -> {
                last = outcome.fix
                stream?.onFix(outcome.fix)
                pending?.send(LocationResult.Located(outcome.fix))
            }

            is AmapFix.Invalid -> {
                stream?.onFailure(outcome.fault)
                pending?.send(LocationResult.Failed(outcome.fault))
            }
        }
    }

    /** One pending single-fix request, so a second caller cannot overwrite the first. */
    private class Waiting(val send: (LocationResult) -> Unit)

    private val noopListener = object : LocationListener {
        override fun onFix(fix: LocationFix) = Unit
    }

    private companion object {
        /** Written into failure details rather than a const so no code reads a fake key by mistake. */
        const val COMPANION_KEY_NOTE = "AMAP_LOCATION_KEY 留空：三方账号未找回前，key 属于待办而不是常量"
    }
}
