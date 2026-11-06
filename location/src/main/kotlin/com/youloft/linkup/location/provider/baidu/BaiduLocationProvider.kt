package com.youloft.linkup.location.provider.baidu

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
 * The Baidu Location SDK adapter — the recovered app's actual source of fixes, reached without a compile
 * time dependency because no copy of the AAR survived the loss: `com.baidu.location.*` exists only inside
 * the two APKs, and there is no public artifact to pull.
 *
 * Reflection here is a stopgap, not a design. Every binding is pinned to a declaration in the decompiled
 * package (`recovered/baidu_location_api_156.txt`), and `BaiduLocationProviderTest` runs this same code
 * against SDK-shaped doubles carrying those signatures, so the mapping logic is really executed offline
 * instead of assumed correct. When the AAR is restored, [BaiduSdk] and the proxy below are replaced by about
 * 120 type-safe lines and nothing else in the layer changes — which is the property that made the interface
 * worth building before the SDK arrives.
 *
 * ## The option block is a copy, not an interpretation
 *
 * `_raw/src166/sources/dg/a.java:98-117` is the option object the shipping app ran; `manager/x0.java:678-691`
 * (1.5.6) is the same block. Only `locationMode` and `scanSpan` ever varied, so those come from
 * [LocationRequest] and the rest stays hard-coded in [BaiduSdk.buildOption], including the fields that look
 * incidental and are not: `coorType = "bd09ll"` (otherwise the fix and the app's own cached stay point sit
 * ~500 m apart in a datum that never existed), `disableLocCache = true` (a cached fix would pass the cadence
 * gate as fresh movement), and `setIsNeedLocationPoiList(true)` (Baidu has no poiName field; the original
 * took the first POI's name, `x0.java:299-304`).
 *
 * @param key the vendor application key from the APK — `ubL50e1vplPn3FsVVbDfQjAcgnU0eZ7P` in both shipped
 *   versions (`LocationClient.setKey`, `manager/x0.java:676`).
 * @param privacyAgreed gates the SDK: with the flag unset `LocationClient` throws instead of locating
 *   (`LocationClient.java:910-911`), which is why it counts against [isInstalled] rather than being
 *   discovered on the first start. `manager/SdkInitManager.java:279-281` is where the original agreed both
 *   the map and the location SDK after the user accepted the privacy policy.
 */
class BaiduLocationProvider(
    private val context: Context,
    private val key: String,
    private val privacyAgreed: () -> Boolean = { true },
    private val sdk: BaiduSdk = BaiduSdk(),
) : LocationProvider {
    private var client: Any? = null

    /** The dynamic proxy handed to the SDK: bound once, and dispatches to whoever is listening now. */
    private var proxy: Any? = null
    private var stream: LocationListener? = null

    /** Set only by [singleFix]; the SDK has no per-request callback, so the one delivery is claimed here. */
    private var oneShot: Waiting? = null
    private var request = LocationRequest.FOREGROUND
    private var last: LocationFix? = null

    override val id = "baidu"

    override val capabilities = LocationCapabilities(
        nativeCoordSystem = CoordSystem.BD09,
        inlinePlace = true,
        poiList = true,
        needsVendorKey = true,
        // 1.6.6 swapped setLocOption on a live client whenever the snapshot changed (s0.java:769-774).
        supportsRuntimeInterval = true,
    )

    /**
     * The AAR being present is necessary but not sufficient: with the privacy flag unset this provider can
     * never produce a fix, so it reports itself not installed and
     * [com.youloft.linkup.location.LocationProviders] falls through to the framework rather than starting a
     * client that throws.
     */
    override val isInstalled: Boolean
        get() = sdk.isPresent && privacyAgreed()

    override fun start(request: LocationRequest, listener: LocationListener): Boolean {
        val client = prepare(listener) ?: return false
        stream = listener
        this.request = request
        val option = sdk.buildOption(request) ?: return false
        if (!sdk.setLocOption(client, option)) return false
        return sdk.start(client)
    }

    override fun update(request: LocationRequest): Boolean {
        val client = client ?: return false
        if (request == this.request) return false
        val option = sdk.buildOption(request) ?: return false
        this.request = request
        return sdk.setLocOption(client, option)
    }

    override fun stop() {
        client?.let { sdk.stop(it) }
    }

    /**
     * `manager/s0.java:3031` did exactly this for `manual_request`: build the option with the once flag,
     * `setLocOption`, then `requestLocation()` and let the ordinary callback answer. So a single fix asked
     * for while following also reaches the follow listener — that is the original's behaviour, not an
     * artifact of this adapter.
     */
    override suspend fun singleFix(request: LocationRequest): LocationResult {
        val client = prepare(noopListener) ?: return LocationResult.Failed(
            LocationFault(LocationFailure.SERVICE_UNAVAILABLE, detail = "百度定位不可用: ${sdk.missingReason}"),
        )
        this.request = request
        val option = sdk.buildOption(request, once = true) ?: return LocationResult.Failed(
            LocationFault(LocationFailure.SERVICE_UNAVAILABLE, detail = "无法构造定位参数"),
        )
        if (!sdk.setLocOption(client, option)) {
            return LocationResult.Failed(LocationFault(LocationFailure.VENDOR_ERROR, detail = "setLocOption 不可用"))
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
     * The SDK has no unregister (`LocationClient` declares only the two register overloads), so the best
     * available teardown is stop-and-drop, which is also why the original kept one client per process.
     */
    override fun close() {
        stop()
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
        // 0 means accepted; anything else the SDK never calls back for, so answer immediately rather than
        // letting the caller sit out the timeout.
        val accepted = sdk.requestLocation(client)
        if (accepted != 0) {
            if (oneShot === waiting) oneShot = null
            continuation.resume(
                LocationResult.Failed(
                    LocationFault(
                        LocationFailure.VENDOR_ERROR,
                        vendorCode = accepted,
                        detail = "requestLocation 未受理，返回 $accepted",
                    ),
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
        if (!privacyAgreed()) {
            listener.onFailure(
                LocationFault(
                    LocationFailure.SERVICE_UNAVAILABLE,
                    detail = "未同意隐私政策：LocationClient 构造时会直接抛异常（LocationClient.java:910-911）",
                ),
            )
            return null
        }
        if (!sdk.setAgreePrivacy()) {
            listener.onFailure(LocationFault(LocationFailure.VENDOR_ERROR, detail = "setAgreePrivacy 不可用"))
            return null
        }
        sdk.setKey(key)
        client?.let { return it }
        val created = sdk.createClient(context) ?: run {
            listener.onFailure(LocationFault(LocationFailure.VENDOR_ERROR, detail = sdk.missingReason))
            return null
        }
        client = created
        val handler = InvocationHandler { _, method: Method, args: Array<Any?>? ->
            if (method.name == "onReceiveLocation") {
                // The original stamped wall clock at callback time and ignored the SDK's own fix time
                // (x0.java:304), so the neutral fix does the same.
                dispatch(sdk.readFix(args?.firstOrNull(), id, System.currentTimeMillis() / 1000))
            }
            null
        }
        val listenerProxy = sdk.makeListener(handler) ?: run {
            listener.onFailure(LocationFault(LocationFailure.VENDOR_ERROR, detail = sdk.missingReason))
            return null
        }
        proxy = listenerProxy
        if (!sdk.registerListener(created, listenerProxy)) {
            listener.onFailure(LocationFault(LocationFailure.VENDOR_ERROR, detail = "registerLocationListener 不可用"))
            client = null
            proxy = null
            return null
        }
        return created
    }

    private fun dispatch(outcome: BaiduFix) {
        val pending = oneShot
        oneShot = null
        when (outcome) {
            is BaiduFix.Ready -> {
                last = outcome.fix
                stream?.onFix(outcome.fix)
                pending?.send(LocationResult.Located(outcome.fix))
            }

            is BaiduFix.Invalid -> {
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
}
