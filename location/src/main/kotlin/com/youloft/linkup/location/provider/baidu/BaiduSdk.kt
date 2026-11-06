package com.youloft.linkup.location.provider.baidu

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
 * The reflective bindings [BaiduLocationProvider] needs, kept separate so the provider still reads like the
 * original Kotlin and this file can be deleted the day the AAR is back.
 *
 * Every string below is a declaration found in the decompiled APK — `recovered/baidu_location_api_156.txt`
 * lists them with file and line, and the doubles under `location/src/test/java/com/baidu/location` carry
 * the same signatures, which is what makes this code path executable offline instead of assumed.
 *
 * Reflection has two failure modes this class is written against, both found by tests rather than reading:
 *
 * - **a void method returns null from `Method.invoke`, so the return value can never mean failure.** Success
 *   is "the method was found and did not throw" ([call], [call0]). Getting this backwards makes every
 *   successful SDK call look like a refusal, and the provider then never starts.
 * - **a nested enum's binary name has exactly one `$`.** `"${'$'}$nested"` style interpolation is easy to
 *   write as two, and `ClassNotFoundException` then silently turns `setLocationMode(null)` into a runtime
 *   call. Missing members here are errors that stop [buildOption], never defaults.
 *
 * Overridable members exist for one reason: tests hand back a double to prove the option block reached the
 * SDK. Nothing in production subclasses this.
 */
open class BaiduSdk {
    private var missing: String? = null

    open val isPresent: Boolean
        get() = classOrNull(CLIENT) != null

    /** Why the SDK is unusable, for logs and for the status screen. */
    open val missingReason: String
        get() = missing ?: ""

    open fun setAgreePrivacy(): Boolean =
        callStatic(CLIENT, "setAgreePrivacy", true)

    open fun setKey(key: String): Boolean =
        callStatic(CLIENT, "setKey", key)

    /** `LocationClient(Context)` declares `throws Exception`; reflection reports that as a cause. */
    open fun createClient(context: Context): Any? = try {
        val client = classOrNull(CLIENT) ?: return null
        client.getConstructor(Context::class.java).newInstance(context.applicationContext)
    } catch (e: ReflectiveOperationException) {
        missing = "LocationClient 构造失败: ${e.cause ?: e}"
        null
    }

    /**
     * The listener has to be a proxy because `BDLocationListener` is an interface
     * (`com/baidu/location/BDLocationListener.java:4-5`) and the SDK exposes
     * `registerLocationListener(BDLocationListener)` beside the abstract-class overload
     * (`LocationClient.java:1203,1213`) — binding the interface is what keeps this compilable with no SDK
     * on the classpath.
     *
     * There is deliberately **no unregister**: neither APK declares one (only the two
     * `registerLocationListener` overloads), which is why `manager/x0.java` builds one client for the life
     * of the process and why [BaiduLocationProvider.close] can only stop and drop it.
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

    open fun registerListener(client: Any, listener: Any): Boolean =
        call(client, "registerLocationListener", listener)

    open fun setLocOption(client: Any, option: Any): Boolean = call(client, "setLocOption", option)

    open fun start(client: Any): Boolean = call0(client, "start")

    open fun stop(client: Any): Boolean = call0(client, "stop")

    /**
     * Baidu answers an int here and the original only logged it (`manager/s0.java:775`), so this hands the
     * number back instead of inventing a meaning for anything but the accepted case.
     */
    open fun requestLocation(client: Any): Int =
        (call0Value(client, "requestLocation") as? Int) ?: REJECTED

    /**
     * The option block from `_raw/src166/sources/dg/a.java:98-117`, transcribed. `scanSpan` and
     * `locationMode` come from [request]; the rest are the constants the shipping app used. Null means a
     * member the original set is not there, which is reported rather than skipped.
     */
    open fun buildOption(request: LocationRequest, once: Boolean = false): Any? = try {
        val optionClass = classOrNull(OPTION) ?: return null
        val option = optionClass.getConstructor().newInstance()
        val setters = listOf(
            "setIsNeedAddress" to request.needsPlace,
            "setIsNeedLocationPoiList" to request.needsPoi,
            "setOpenGnss" to true,
            "setEnableSimulateGnss" to false,
            "setOnceLocation" to once,
        )
        setters.forEach { (name, value) ->
            if (!call(option, name, value)) return null.also { missing = "LocationClientOption.$name 不存在" }
        }
        val mode = enumConstant(optionClass, "LocationMode", modeName(request.accuracy)) ?: return null
        if (!call(option, "setLocationMode", mode)) {
            return null.also { missing = "LocationClientOption.setLocationMode 不存在" }
        }
        val fields = listOf(
            "scanSpan" to request.intervalMillis.toInt(),
            "coorType" to COOR_TYPE,
            // setIsNeedAddress already writes this inside the SDK; the original assigned it too, and so do we.
            "addrType" to if (request.needsPlace) "all" else "noaddr",
            "disableLocCache" to !request.allowCachedFixes,
            "isIgnoreKillProcess" to true,
            "wifiCacheTimeOut" to WIFI_CACHE_TIMEOUT_MILLIS,
            "isEnableBeidouMode" to true,
        )
        fields.forEach { (name, value) ->
            if (!setField(option, name, value)) return null.also { missing = "LocationClientOption.$name 写不进去" }
        }
        val firstLocType = enumConstant(optionClass, "FirstLocType", "ACCURACY_IN_FIRST_LOC") ?: return null
        if (!setField(option, "firstLocType", firstLocType)) {
            return null.also { missing = "LocationClientOption.firstLocType 写不进去" }
        }
        option
    } catch (e: ReflectiveOperationException) {
        missing = "构造 LocationClientOption 失败: ${e.cause ?: e}"
        null
    }

    /**
     * Reads one `BDLocation` into the neutral model, reproducing `manager/x0.java:284-314` rule for rule:
     * placeholder coordinates are rejected instead of reported, speed is only trusted on a GNSS fix and is
     * divided out of km/h, accuracy is `getRadius()`, and the POI name is the *first* POI's name — or the
     * empty string when the list is missing or empty, because Baidu has no poiName field of its own and the
     * original wrote `""` rather than null (`x0.java:299-304`).
     *
     * The datum is read from the SDK rather than assumed, because `coorType` is a string the remote config
     * could change; a build that answers gcj02 is then still normalized correctly.
     */
    open fun readFix(raw: Any?, providerId: String, stampEpochSeconds: Long): BaiduFix {
        if (raw == null) return BaiduFix.Invalid(LocationFault(LocationFailure.INVALID_FIX, detail = "BDLocation 为 null"))
        val lat = number(raw, "getLatitude") ?: return invalid(raw, "BDLocation 没有 getLatitude")
        val lng = number(raw, "getLongitude") ?: return invalid(raw, "BDLocation 没有 getLongitude")
        val locType = (get(raw, "getLocType") as? Int) ?: 0
        if (isPlaceholder(lat) || isPlaceholder(lng)) {
            return BaiduFix.Invalid(
                LocationFault(LocationFailure.INVALID_FIX, vendorCode = locType, detail = "$lat,$lng"),
            )
        }
        val rawSpeed = (get(raw, "getSpeed") as? Float) ?: 0f
        val speed = if (locType == TYPE_GNSS) rawSpeed / 3.6f else 0f
        val place = Place(
            province = get(raw, "getProvince") as? String,
            city = get(raw, "getCity") as? String,
            district = get(raw, "getDistrict") as? String,
            street = get(raw, "getStreet") as? String,
            streetNumber = get(raw, "getStreetNumber") as? String,
            poiName = firstPoiName(get(raw, "getPoiList")),
        )
        return BaiduFix.Ready(
            LocationFix.normalized(
                lat = lat,
                lng = lng,
                system = CoordSystem.parse(get(raw, "getCoorType") as? String),
                epochSeconds = stampEpochSeconds,
                accuracyMeters = (get(raw, "getRadius") as? Float) ?: 0f,
                speedMetersPerSecond = if (speed.isInfinite() || speed.isNaN()) 0f else speed,
                providerId = providerId,
                place = place,
                vendorFixType = locType,
            ),
        )
    }

    /**
     * One reflective call: found and did not throw. `Unit`/void methods return null from `invoke`, which is
     * why nothing here looks at the returned value.
     */
    private fun call(target: Any, name: String, argument: Any?): Boolean {
        val method = matchingMethod(target.javaClass, name, argument) ?: return false
        return runCatching { method.invoke(target, argument) }.isSuccess
    }

    private fun call0(target: Any, name: String): Boolean {
        val method = target.noArgMethod(name) ?: return false
        return runCatching { method.invoke(target) }.isSuccess
    }

    private fun call0Value(target: Any, name: String): Any? {
        val method = target.noArgMethod(name) ?: return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun callStatic(className: String, name: String, argument: Any): Boolean {
        val clazz = classOrNull(className) ?: return false
        val method = matchingMethod(clazz, name, argument) ?: return false
        return runCatching { method.invoke(null, argument) }.isSuccess
    }

    private fun Any.noArgMethod(name: String): Method? =
        javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }

    private fun setField(target: Any, name: String, value: Any?): Boolean {
        val field = runCatching { target.javaClass.getField(name) }.getOrNull() ?: return false
        return runCatching { field.set(target, value) }.isSuccess
    }

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

    /** `LocationClientOption$LocationMode` — one `$`, the JVM binary name of the nested enum. */
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

    private fun invalid(raw: Any?, detail: String) = BaiduFix.Invalid(
        LocationFault(LocationFailure.VENDOR_ERROR, vendorCode = (get(raw, "getLocType") as? Int) ?: 0, detail = detail),
    )

    private fun isPlaceholder(value: Double) = value == 0.0 || value == Double.MIN_VALUE

    private fun modeName(accuracy: LocationAccuracy) = when (accuracy) {
        LocationAccuracy.HIGH -> "Hight_Accuracy"
        LocationAccuracy.BATTERY_SAVING -> "Battery_Saving"
    }

    private fun firstPoiName(list: Any?): String =
        ((list as? List<*>)?.firstOrNull()?.let { get(it, "getName") as? String }) ?: ""

    private fun number(target: Any, name: String): Double? = get(target, name) as? Double

    private fun get(target: Any?, name: String): Any? {
        if (target == null) return null
        val method = target.noArgMethod(name) ?: return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun classOrNull(name: String): Class<*>? = try {
        Class.forName(name)
    } catch (e: ClassNotFoundException) {
        missing = "找不到 $name：本 build 未包含百度定位 AAR"
        null
    }

    companion object {
        private const val CLIENT = "com.baidu.location.LocationClient"
        private const val OPTION = "com.baidu.location.LocationClientOption"
        private const val LISTENER = "com.baidu.location.BDLocationListener"

        /** `dg/a.java:76-79` — the shipping app asked for Baidu's own datum. */
        const val COOR_TYPE = "bd09ll"

        /** `dg/a.java:100-117`. */
        const val WIFI_CACHE_TIMEOUT_MILLIS = 120_000

        /** `BDLocation.java:93-94`: TypeGnssLocation == TypeGpsLocation == 61. */
        const val TYPE_GNSS = 61

        /** Anything but this means `requestLocation()` could not be reached at all. */
        const val REJECTED = -1
    }
}

/** Reading a vendor fix either produced a neutral one or explained why this sample is unusable. */
sealed class BaiduFix {
    data class Ready(val fix: LocationFix) : BaiduFix()
    data class Invalid(val fault: LocationFault) : BaiduFix()
}
