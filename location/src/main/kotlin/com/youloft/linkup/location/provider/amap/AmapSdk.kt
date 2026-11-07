package com.youloft.linkup.location.provider.amap

import android.content.Context
import com.youloft.linkup.location.CoordSystem
import com.youloft.linkup.location.LocationAccuracy
import com.youloft.linkup.location.LocationFault
import com.youloft.linkup.location.LocationFailure
import com.youloft.linkup.location.LocationFix
import com.youloft.linkup.location.LocationRequest
import com.youloft.linkup.location.Place
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * The reflective bindings [AmapLocationProvider] needs, kept separate so the provider still reads like the
 * interface layer and this file can be deleted the day a type-safe build is preferred.
 *
 * **Where the contract comes from — and where it does not.** Baidu's twin ([BaiduSdk]) transcribes the
 * decompiled APK, because Baidu is what shipped. The original app never carried AMap: this vendor is here
 * because the layer's whole point is that a *second* provider must slot in without touching anything above
 * [com.youloft.linkup.location.LocationProvider]. So every member bound below is pinned to the **official
 * AMap location SDK API** (`com.amap.api.location`, the 3D-map-free location AAR), the doubles under
 * `location/src/test/java/com/amap/api/location` carry those signatures, and the option values are marked
 * as this project's choices rather than a recovered block. Nothing here may claim "原版如此".
 *
 * The two failure modes are the ones the Baidu binding already paid for, so the same defences apply:
 *
 * - **a void method returns null from `Method.invoke`** — success is "found and did not throw" ([call],
 *   [callStatic]). AMap's `startLocation`/`stopLocation`/`requestLocation` are all void.
 * - **a nested enum's binary name has exactly one `$`** — `AMapLocationClientOption$AMapLocationMode`.
 *   A missing member stops [buildOption] with a reason; it is never skipped into a default.
 *
 * Overridable members exist so tests can hand in an inspectable double; nothing in production subclasses
 * this.
 */
open class AmapSdk {
    private var missing: String? = null

    open val isPresent: Boolean
        get() = classOrNull(CLIENT) != null

    /** Why the SDK is unusable, for logs and for the status screen. */
    open val missingReason: String
        get() = missing ?: ""

    /**
     * AMap's two-step privacy gate, required before the client constructor is legal on the privacy-compliant
     * SDKs (5.4.0+): `updatePrivacyShow(context, true, true)` then `updatePrivacyAgree(context, true)`.
     * This is the SDK's own requirement, the same one that makes an unagreed Baidu client throw.
     */
    open fun agreePrivacy(context: Context): Boolean {
        if (!callStaticArgs(CLIENT, "updatePrivacyShow", arrayOf(context, true, true))) {
            missing = "AMapLocationClient.updatePrivacyShow 不可用"
            return false
        }
        if (!callStaticArgs(CLIENT, "updatePrivacyAgree", arrayOf(context, true))) {
            missing = "AMapLocationClient.updatePrivacyAgree 不可用"
            return false
        }
        return true
    }

    /** `AMapLocationClient.setApiKey(String)` — the vendor key is a static, not a client field. */
    open fun setKey(key: String): Boolean = callStatic(CLIENT, "setApiKey", key)

    open fun createClient(context: Context): Any? = try {
        val client = classOrNull(CLIENT) ?: return null
        client.getConstructor(Context::class.java).newInstance(context.applicationContext)
    } catch (e: ReflectiveOperationException) {
        missing = "AMapLocationClient 构造失败: ${e.cause ?: e}"
        null
    }

    /**
     * The listener has to be a proxy because `AMapLocationListener` is an interface (single method
     * `onLocationChanged(AMapLocation)`) and binding the interface is what keeps this compilable with no AAR
     * on the classpath. Unlike Baidu, the official SDK also declares an unregister
     * (`unregisterLocationListener`); it is deliberately not bound — one client per process, stop-and-drop
     * teardown, keeps the two adapters symmetric and [AmapLocationProvider.close] honest about what it
     * releases.
     */
    open fun makeListener(handler: InvocationHandler): Any? = try {
        val listener = classOrNull(LISTENER) ?: return null
        if (!listener.isInterface) {
            missing = "$LISTENER 不是接口，无法动态代理"
            return null
        }
        java.lang.reflect.Proxy.newProxyInstance(listener.classLoader, arrayOf(listener), handler)
    } catch (e: ReflectiveOperationException) {
        missing = "创建定位监听失败: ${e.cause ?: e}"
        null
    }

    open fun setLocationListener(client: Any, listener: Any): Boolean =
        call(client, "setLocationListener", listener)

    open fun setLocationOption(client: Any, option: Any): Boolean =
        call(client, "setLocationOption", option)

    open fun start(client: Any): Boolean = call0(client, "startLocation")

    open fun stop(client: Any): Boolean = call0(client, "stopLocation")

    /** The SDK's own release call; part of [AmapLocationProvider.close], not of stop. */
    open fun destroy(client: Any): Boolean = call0(client, "onDestroy")

    /**
     * The option block for one [LocationRequest]. Deliberately minimal: mode, interval, address, once are
     * the four knobs the request actually carries; everything else stays at the SDK's defaults **because
     * this is a choice of this project, not a recovered constant** — the original app never configured
     * AMap. Null means a member the request needs is not there, which is reported rather than skipped.
     */
    open fun buildOption(request: LocationRequest, once: Boolean = false): Any? = try {
        val optionClass = classOrNull(OPTION) ?: return null
        val option = optionClass.getConstructor().newInstance()
        val mode = enumConstant(optionClass, "AMapLocationMode", modeName(request.accuracy)) ?: return null
        val setters = listOf(
            "setLocationMode" to mode,
            "setInterval" to request.intervalMillis,
            "setNeedAddress" to request.needsPlace,
            "setOnceLocation" to once,
        )
        setters.forEach { (name, value) ->
            if (!call(option, name, value)) return null.also { missing = "AMapLocationClientOption.$name 不存在" }
        }
        option
    } catch (e: ReflectiveOperationException) {
        missing = "构造 AMapLocationClientOption 失败: ${e.cause ?: e}"
        null
    }

    /**
     * Reads one `AMapLocation` into the neutral model. AMap's error contract is unlike Baidu's: the
     * callback fires for failures too, and `getErrorCode() != 0` is the failure — so the error gate comes
     * before any coordinate is read. `getSpeed()` is inherited from `android.location.Location` and is
     * **already m/s** — the km/h division that shaped Baidu's `locType == 61` rule has no counterpart here,
     * and dividing again would make the cadence 3.6× stingier. The datum is read off the fix
     * (`getCoordType()` answers `GCJ02`) rather than assumed.
     */
    open fun readFix(raw: Any?, providerId: String, stampEpochSeconds: Long): AmapFix {
        if (raw == null) return AmapFix.Invalid(LocationFault(LocationFailure.INVALID_FIX, detail = "AMapLocation 为 null"))
        val errorCode = (get(raw, "getErrorCode") as? Int) ?: 0
        if (errorCode != 0) {
            return AmapFix.Invalid(
                LocationFault(
                    LocationFailure.VENDOR_ERROR,
                    vendorCode = errorCode,
                    detail = (get(raw, "getErrorInfo") as? String) ?: "",
                ),
            )
        }
        val lat = number(raw, "getLatitude") ?: return invalid(raw, "AMapLocation 没有 getLatitude")
        val lng = number(raw, "getLongitude") ?: return invalid(raw, "AMapLocation 没有 getLongitude")
        if (isPlaceholder(lat) || isPlaceholder(lng)) {
            return AmapFix.Invalid(
                LocationFault(LocationFailure.INVALID_FIX, detail = "$lat,$lng"),
            )
        }
        val speed = (get(raw, "getSpeed") as? Float) ?: 0f
        val place = Place(
            province = get(raw, "getProvince") as? String,
            city = get(raw, "getCity") as? String,
            district = get(raw, "getDistrict") as? String,
            street = get(raw, "getStreet") as? String,
            streetNumber = get(raw, "getStreetNum") as? String,
            poiName = get(raw, "getPoiName") as? String,
        )
        return AmapFix.Ready(
            LocationFix.normalized(
                lat = lat,
                lng = lng,
                system = CoordSystem.parse(get(raw, "getCoordType") as? String),
                epochSeconds = stampEpochSeconds,
                accuracyMeters = (get(raw, "getAccuracy") as? Float) ?: 0f,
                speedMetersPerSecond = if (speed.isInfinite() || speed.isNaN()) 0f else speed,
                providerId = providerId,
                bearingDegrees = get(raw, "getBearing") as? Float,
                place = place,
                vendorFixType = (get(raw, "getLocationType") as? Int) ?: 0,
            ),
        )
    }

    /** One reflective call on an instance: found and did not throw. Void methods return null from `invoke`. */
    private fun call(target: Any, name: String, argument: Any?): Boolean {
        val method = matchingMethod(target.javaClass, name, argument) ?: return false
        return runCatching { method.invoke(target, argument) }.isSuccess
    }

    private fun call0(target: Any, name: String): Boolean {
        val method = target.noArgMethod(name) ?: return false
        return runCatching { method.invoke(target) }.isSuccess
    }

    private fun callStatic(className: String, name: String, argument: Any): Boolean {
        val clazz = classOrNull(className) ?: return false
        val method = matchingMethod(clazz, name, argument) ?: return false
        return runCatching { method.invoke(null, argument) }.isSuccess
    }

    /** Static calls with several parameters — AMap's privacy pair is the only user. */
    private fun callStaticArgs(className: String, name: String, args: Array<Any?>): Boolean {
        val clazz = classOrNull(className) ?: return false
        val method = clazz.methods.firstOrNull { it.name == name && it.parameterCount == args.size && args.indices.all { i -> it.parameterTypes[i].accepts(args[i]) } }
            ?: return false
        return runCatching { method.invoke(null, *args) }.isSuccess
    }

    private fun Any.noArgMethod(name: String): Method? =
        javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }

    /**
     * Primitive parameters never match `isInstance`, so they are compared against the boxed type of the
     * argument; everything else, enum constants included, is an ordinary `isInstance`.
     */
    private fun matchingMethod(clazz: Class<*>, name: String, argument: Any?): Method? =
        clazz.methods.firstOrNull { it.name == name && it.parameterCount == 1 && it.parameterTypes[0].accepts(argument) }

    private fun Class<*>.accepts(argument: Any?): Boolean = when {
        argument == null -> !isPrimitive
        isPrimitive -> boxed() == argument.javaClass
        else -> isInstance(argument)
    }

    private fun Class<*>.boxed(): Class<*> = when (this) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> this
    }

    /** `AMapLocationClientOption$AMapLocationMode` — one `$`, the JVM binary name of the nested enum. */
    private fun enumConstant(owner: Class<*>, nested: String, name: String): Any? {
        val binaryName = owner.name + "$" + nested
        val enumClass = try {
            Class.forName(binaryName)
        } catch (e: ClassNotFoundException) {
            missing = "找不到 $binaryName"
            return null
        }
        val constant = enumClass.enumConstants?.firstOrNull { (it as Enum<*>).name == name }
        if (constant == null) missing = "$binaryName 里没有 $name"
        return constant
    }

    private fun invalid(raw: Any?, detail: String) = AmapFix.Invalid(
        LocationFault(LocationFailure.VENDOR_ERROR, vendorCode = (get(raw, "getErrorCode") as? Int) ?: 0, detail = detail),
    )

    private fun isPlaceholder(value: Double) = value == 0.0 || value == Double.MIN_VALUE

    private fun modeName(accuracy: LocationAccuracy) = when (accuracy) {
        LocationAccuracy.HIGH -> "Hight_Accuracy"
        LocationAccuracy.BATTERY_SAVING -> "Battery_Saving"
    }

    private fun number(target: Any, name: String): Double? = get(target, name) as? Double

    private fun get(target: Any?, name: String): Any? {
        if (target == null) return null
        val method = target.noArgMethod(name) ?: return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun classOrNull(name: String): Class<*>? = try {
        Class.forName(name)
    } catch (e: ClassNotFoundException) {
        missing = "找不到 $name：本 build 未包含高德定位 AAR"
        null
    }

    companion object {
        private const val CLIENT = "com.amap.api.location.AMapLocationClient"
        private const val OPTION = "com.amap.api.location.AMapLocationClientOption"
        private const val LISTENER = "com.amap.api.location.AMapLocationListener"

        /** What `AMapLocation.getCoordType()` answers; also what [CoordSystem.parse] maps back to GCJ02. */
        const val COOR_TYPE = "GCJ02"
    }
}

/** Reading a vendor fix either produced a neutral one or explained why this sample is unusable. */
sealed class AmapFix {
    data class Ready(val fix: LocationFix) : AmapFix()
    data class Invalid(val fault: LocationFault) : AmapFix()
}
