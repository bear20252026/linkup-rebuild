package com.youloft.linkup.map.provider.baidu

import android.content.Context
import android.os.Bundle

/**
 * The reflective bindings [BaiduTrackMapProvider] needs, kept separate so the map reads like the interface
 * layer and this file can be deleted the day the AAR is back.
 *
 * **Contract note, same honesty rule as the location layer's Baidu binding:** every member bound below is
 * a declaration of the official Baidu map SDK (`com.baidu.mapapi.*`, the map AAR — a different artifact
 * from the location AAR the collector waits for). The shipping app's map was Baidu, but the *track page*
 * never reached the map in the recovered code, so there is no decompiled call block to transcribe; the
 * values here (line width, zoom) are marked as this project's choices. The doubles under
 * `map/src/test/java/com/baidu/mapapi` carry the same signatures.
 *
 * The two reflection failure modes the location binding paid for are defended the same way: a void method
 * succeeds when found and unthrown ([call0]), and nested-class binary names get exactly one `$`.
 */
open class BaiduMapSdk {
    private var missing: String? = null
    private var initialized = false

    open val isPresent: Boolean
        get() = classOrNull(MAP_VIEW) != null

    open val missingReason: String
        get() = missing ?: ""

    /**
     * The map SDK's own gate: `SDKInitializer.setAgreePrivacy(true)` then `SDKInitializer.initialize(context)`,
     * once per process, before any map view is constructed. Re-calling `initialize` after the SDK is up is
     * the caller's mistake to avoid, which the [initialized] flag does.
     */
    open fun ensureInitialized(context: Context, privacyAgreed: Boolean): Boolean {
        if (initialized) return true
        if (!privacyAgreed) {
            missing = "未同意隐私政策：地图 SDK 初始化被跳过"
            return false
        }
        if (!callStatic(INITIALIZER, "setAgreePrivacy", true)) {
            missing = "SDKInitializer.setAgreePrivacy 不可用"
            return false
        }
        if (!callStaticArgs(INITIALIZER, "initialize", arrayOf(context.applicationContext))) {
            missing = "SDKInitializer.initialize 不可用"
            return false
        }
        initialized = true
        return true
    }

    /** Visible to tests, which reset it between runs. */
    open fun markInitialized() {
        initialized = true
    }

    open fun createMapView(context: Context): Any? = try {
        val clazz = classOrNull(MAP_VIEW) ?: return null
        clazz.getConstructor(Context::class.java).newInstance(context.applicationContext)
    } catch (e: ReflectiveOperationException) {
        missing = "TextureMapView 构造失败: ${e.cause ?: e}"
        null
    }

    open fun onCreate(mapView: Any, context: Context, state: Bundle?): Boolean =
        callArgs(mapView, "onCreate", arrayOf(context.applicationContext, state))

    open fun onResume(mapView: Any): Boolean = call0(mapView, "onResume")

    open fun onPause(mapView: Any): Boolean = call0(mapView, "onPause")

    open fun onDestroy(mapView: Any): Boolean = call0(mapView, "onDestroy")

    open fun onSaveInstanceState(mapView: Any): Bundle? {
        val method = mapView.javaClass.methods.firstOrNull { it.name == "onSaveInstanceState" && it.parameterCount == 0 }
            ?: return null
        return runCatching { method.invoke(mapView) as? Bundle }.getOrNull()
    }

    open fun getMap(mapView: Any): Any? {
        val method = mapView.javaClass.methods.firstOrNull { it.name == "getMap" && it.parameterCount == 0 }
            ?: return null
        return runCatching { method.invoke(mapView) }.getOrNull()
    }

    /** Vendor LatLng — Baidu's container is datum-agnostic; the *map* renders BD09, so callers convert. */
    open fun latLng(lat: Double, lng: Double): Any? = try {
        val clazz = classOrNull(LAT_LNG) ?: return null
        clazz.getConstructor(Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
            .newInstance(lat, lng)
    } catch (e: ReflectiveOperationException) {
        missing = "LatLng 构造失败: ${e.cause ?: e}"
        null
    }

    /** `PolylineOptions().points(list).width(px).color(argb)` — width and color are this project's choice. */
    open fun polyline(points: List<Any>): Any? = try {
        val clazz = classOrNull(POLYLINE_OPTIONS) ?: return null
        val options = clazz.getConstructor().newInstance()
        if (!call(options, "points", points)) return null.also { missing = "PolylineOptions.points 不可用" }
        if (!call(options, "width", TRACK_LINE_WIDTH_PX)) return null.also { missing = "PolylineOptions.width 不可用" }
        if (!call(options, "color", TRACK_LINE_COLOR)) return null.also { missing = "PolylineOptions.color 不可用" }
        options
    } catch (e: ReflectiveOperationException) {
        missing = "构造 PolylineOptions 失败: ${e.cause ?: e}"
        null
    }

    /** `MarkerOptions().position(latLng)` — the stay pin, one per arrival row. */
    open fun marker(latLng: Any): Any? = try {
        val clazz = classOrNull(MARKER_OPTIONS) ?: return null
        val options = clazz.getConstructor().newInstance()
        if (!call(options, "position", latLng)) return null.also { missing = "MarkerOptions.position 不可用" }
        options
    } catch (e: ReflectiveOperationException) {
        missing = "构造 MarkerOptions 失败: ${e.cause ?: e}"
        null
    }

    open fun addOverlay(map: Any, options: Any): Boolean = call(map, "addOverlay", options)

    open fun clear(map: Any): Boolean = call0(map, "clear")

    /** `MapStatusUpdateFactory.newLatLngZoom(latLng, zoom)` + `BaiduMap.setMapStatus(update)`. */
    open fun moveCamera(map: Any, latLng: Any, zoom: Float): Boolean {
        val update = try {
            val factory = classOrNull(STATUS_FACTORY) ?: return false
            val method = factory.methods.firstOrNull {
                it.name == "newLatLngZoom" && it.parameterCount == 2
            } ?: return false.also { missing = "MapStatusUpdateFactory.newLatLngZoom 不可用" }
            runCatching { method.invoke(null, latLng, zoom) }.getOrNull() ?: return false
        } catch (e: ReflectiveOperationException) {
            missing = "构造 MapStatusUpdate 失败: ${e.cause ?: e}"
            return false
        }
        return call(map, "setMapStatus", update)
    }

    private fun call(target: Any, name: String, argument: Any?): Boolean {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 1 && it.parameterTypes[0].accepts(argument)
        } ?: return false
        return runCatching { method.invoke(target, argument) }.isSuccess
    }

    private fun callArgs(target: Any, name: String, args: Array<Any?>): Boolean {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == args.size
        } ?: return false
        return runCatching { method.invoke(target, *args) }.isSuccess
    }

    private fun call0(target: Any, name: String): Boolean {
        val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?: return false
        return runCatching { method.invoke(target) }.isSuccess
    }

    private fun callStatic(className: String, name: String, argument: Any): Boolean {
        val clazz = classOrNull(className) ?: return false
        val method = clazz.methods.firstOrNull {
            it.name == name && it.parameterCount == 1 && it.parameterTypes[0].accepts(argument)
        } ?: return false
        return runCatching { method.invoke(null, argument) }.isSuccess
    }

    private fun callStaticArgs(className: String, name: String, args: Array<Any?>): Boolean {
        val clazz = classOrNull(className) ?: return false
        val method = clazz.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: return false
        return runCatching { method.invoke(null, *args) }.isSuccess
    }

    /**
     * Primitive parameters never match `isInstance`, so they are compared against the boxed type of the
     * argument — the exact trap the location layer's Baidu binding documented after paying for it.
     */
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

    private fun classOrNull(name: String): Class<*>? = try {
        Class.forName(name)
    } catch (e: ClassNotFoundException) {
        missing = "找不到 $name：本 build 未包含百度地图 AAR"
        null
    }

    companion object {
        private const val INITIALIZER = "com.baidu.mapapi.SDKInitializer"
        private const val MAP_VIEW = "com.baidu.mapapi.map.TextureMapView"
        private const val LAT_LNG = "com.baidu.mapapi.model.LatLng"
        private const val POLYLINE_OPTIONS = "com.baidu.mapapi.map.PolylineOptions"
        private const val MARKER_OPTIONS = "com.baidu.mapapi.map.MarkerOptions"
        private const val STATUS_FACTORY = "com.baidu.mapapi.map.MapStatusUpdateFactory"

        /** Track-viewing zoom, line weight and line colour — this project's choices, not recovered constants. */
        const val TRACK_ZOOM = 14f
        const val TRACK_LINE_WIDTH_PX = 12
        const val TRACK_LINE_COLOR = 0xFF2C7BE5.toInt()
    }
}
