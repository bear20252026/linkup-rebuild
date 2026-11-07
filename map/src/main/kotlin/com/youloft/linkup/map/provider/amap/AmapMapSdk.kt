package com.youloft.linkup.map.provider.amap

import android.os.Bundle

/**
 * The reflective bindings [AmapTrackMapProvider] needs, pinned to the official AMap map SDK API
 * (`com.amap.api.maps.*`). The original never shipped AMap — this is the second vendor proving the seam —
 * so the values here are this project's choices and nothing may cite them as recovered behaviour. The
 * doubles under `map/src/test/java/com/amap/api/maps` carry the same signatures.
 *
 * Note a vendor fact the adapter cannot hide: the AMap **map** SDK reads its key from manifest metadata
 * (`com.amap.api.v2.apikey`), not from a static call, so a key cannot be injected reflectively — the
 * manifest that ships with the future AAR carries it. [isInstalled] therefore checks the classes only and
 * says nothing about authorization.
 */
open class AmapMapSdk {
    private var missing: String? = null

    open val isPresent: Boolean
        get() = classOrNull(MAP_VIEW) != null

    open val missingReason: String
        get() = missing ?: ""

    open fun createMapView(context: android.content.Context): Any? = try {
        val clazz = classOrNull(MAP_VIEW) ?: return null
        clazz.getConstructor(android.content.Context::class.java)
            .newInstance(context.applicationContext)
    } catch (e: ReflectiveOperationException) {
        missing = "AMap TextureMapView 构造失败: ${e.cause ?: e}"
        null
    }

    /** AMap's map view takes only the state bundle here — no context, unlike Baidu's. */
    open fun onCreate(mapView: Any, state: Bundle?): Boolean = callArgs(mapView, "onCreate", arrayOf(state))

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

    /** Vendor LatLng; AMap's canvas renders GCJ02, so callers convert from WGS84 before this. */
    open fun latLng(lat: Double, lng: Double): Any? = try {
        val clazz = classOrNull(LAT_LNG) ?: return null
        clazz.getConstructor(Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
            .newInstance(lat, lng)
    } catch (e: ReflectiveOperationException) {
        missing = "LatLng 构造失败: ${e.cause ?: e}"
        null
    }

    /** `PolylineOptions().add(each)` — AMap builds the point list with `add`, not a single `points` call. */
    open fun polyline(points: List<Any>): Any? = try {
        val clazz = classOrNull(POLYLINE_OPTIONS) ?: return null
        val options = clazz.getConstructor().newInstance()
        points.forEach { latLng ->
            if (!call(options, "add", latLng)) return null.also { missing = "PolylineOptions.add 不可用" }
        }
        if (!call(options, "width", TRACK_LINE_WIDTH_DP.toFloat())) {
            return null.also { missing = "PolylineOptions.width 不可用" }
        }
        if (!call(options, "color", TRACK_LINE_COLOR)) return null.also { missing = "PolylineOptions.color 不可用" }
        options
    } catch (e: ReflectiveOperationException) {
        missing = "构造 PolylineOptions 失败: ${e.cause ?: e}"
        null
    }

    /** `MarkerOptions().position(latLng)` — the stay pin. */
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

    /** `CameraUpdateFactory.newLatLngZoom(latLng, zoom)` + `AMap.moveCamera(update)`. */
    open fun moveCamera(map: Any, latLng: Any, zoom: Float): Boolean {
        val update = try {
            val factory = classOrNull(CAMERA_FACTORY) ?: return false
            val method = factory.methods.firstOrNull {
                it.name == "newLatLngZoom" && it.parameterCount == 2
            } ?: return false.also { missing = "CameraUpdateFactory.newLatLngZoom 不可用" }
            runCatching { method.invoke(null, latLng, zoom) }.getOrNull() ?: return false
        } catch (e: ReflectiveOperationException) {
            missing = "构造 CameraUpdate 失败: ${e.cause ?: e}"
            return false
        }
        return call(map, "moveCamera", update)
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

    /**
     * Primitive parameters never match `isInstance`, so they are compared against the boxed type of the
     * argument — the exact trap the location layer's binding documented after paying for it.
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
        missing = "找不到 $name：本 build 未包含高德地图 AAR"
        null
    }

    companion object {
        private const val MAP_VIEW = "com.amap.api.maps.TextureMapView"
        private const val LAT_LNG = "com.amap.api.maps.model.LatLng"
        private const val POLYLINE_OPTIONS = "com.amap.api.maps.model.PolylineOptions"
        private const val MARKER_OPTIONS = "com.amap.api.maps.model.MarkerOptions"
        private const val CAMERA_FACTORY = "com.amap.api.maps.CameraUpdateFactory"

        /** Track-viewing zoom, line weight (dp — AMap's width is dp, Baidu's px) and colour: our choices. */
        const val TRACK_ZOOM = 14f
        const val TRACK_LINE_WIDTH_DP = 8
        const val TRACK_LINE_COLOR = 0xFF2C7BE5.toInt()
    }
}
