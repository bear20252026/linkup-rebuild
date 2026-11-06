package com.youloft.linkup.location

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The three datums a Chinese location SDK can hand back, and the only math the location layer is
 * allowed to do on its own — that is what makes the rest of the interface vendor-neutral.
 *
 * Why this file exists rather than `com.baidu.mapapi.model.LatLng`: the recovered app stores **WGS84**
 * and only converts on the way in and out. `_raw/out_jadx_156/sources/com/youloft/watcher/manager/x0.java:290`
 * turns every Baidu fix with `h0.c(lat, lng)` (bd09ll -> gcj02 -> wgs84) before it reaches the bean, and
 * `bean/Location.java:222 getLatLng09()` converts back when a distance is needed. A provider that silently
 * returned gcj02 (AMAP/Tencent's default) would therefore be ~500 m off against the app's own cached stay
 * point, so the datum is part of the model, not an assumption.
 *
 * The conversions are a literal port of `_raw/out_jadx_156/sources/com/youloft/watcher/utils/h0.java`;
 * `GeoMathTest` pins them to `recovered/geo_golden_156.txt`, which was produced by running that file's
 * arithmetic on the JDK.
 */
enum class CoordSystem {
    WGS84,
    GCJ02,
    BD09;

    companion object {
        /** Baidu spells its datum `bd09ll`; other SDKs use plain names. Unknown spellings stay WGS84. */
        fun parse(raw: String?): CoordSystem = when (raw?.trim()?.lowercase()) {
            "bd09", "bd09ll" -> BD09
            "gcj02", "gcj02ll" -> GCJ02
            else -> WGS84
        }
    }
}

data class Coord(val lat: Double, val lng: Double, val system: CoordSystem = CoordSystem.WGS84) {
    /**
     * The app's currency. Anything already WGS84 is returned untouched — `h0.g`/`h0.n` short-circuit
     * outside the China bbox (`h0.java:71`, `h0.java:142`) and dropping that guard would offset overseas
     * fixes.
     */
    fun asWgs84(): Coord = when (system) {
        CoordSystem.WGS84 -> this
        CoordSystem.GCJ02 -> gcj02ToWgs84(this)
        CoordSystem.BD09 -> gcj02ToWgs84(bd09ToGcj02(this))
    }

    fun asBd09(): Coord = when (system) {
        CoordSystem.BD09 -> this
        CoordSystem.GCJ02 -> gcj02ToBd09(this)
        CoordSystem.WGS84 -> wgs84ToBd09(this)
    }

    /** `true` when the SDK reported a coordinate the layer cannot place (0,0 / Double.MIN_VALUE). */
    val isPlaceholder: Boolean
        get() = lat == 0.0 || lng == 0.0 || lat == Double.MIN_VALUE || lng == Double.MIN_VALUE
}

/** bd09 -> gcj02 -> wgs84, the exact chain `h0.c` performs on a Baidu fix. */
fun bd09ToWgs84(coord: Coord): Coord = gcj02ToWgs84(bd09ToGcj02(coord))

/**
 * wgs84 -> gcj02 -> bd09, the exact chain `h0.m` performs for map display. Note that the China guard
 * lives only in the gcj02 step (`h0.java:142`): `h0.java:137` still adds the bd09 offset outside China,
 * which the golden sample for `(0.5, 100.0)` pins.
 */
fun wgs84ToBd09(coord: Coord): Coord = gcj02ToBd09(wgs84ToGcj02(coord))

fun bd09ToGcj02(coord: Coord): Coord {
    val dx = coord.lng - 0.0065
    val dy = coord.lat - 0.006
    val r = sqrt(dx * dx + dy * dy) - sin(dy * X_PI) * 2.0e-5
    val theta = atan2(dy, dx) - cos(dx * X_PI) * 3.0e-6
    return Coord(sin(theta) * r, cos(theta) * r, CoordSystem.GCJ02)
}

fun gcj02ToBd09(coord: Coord): Coord {
    val r = sqrt(coord.lng * coord.lng + coord.lat * coord.lat) + sin(coord.lat * X_PI) * 2.0e-5
    val theta = atan2(coord.lat, coord.lng) + cos(coord.lng * X_PI) * 3.0e-6
    return Coord(sin(theta) * r + 0.006, cos(theta) * r + 0.0065, CoordSystem.BD09)
}

fun gcj02ToWgs84(coord: Coord): Coord =
    if (outOfChina(coord.lat, coord.lng)) Coord(coord.lat, coord.lng, CoordSystem.WGS84)
    else Coord(coord.lat - deltaLat(coord.lat, coord.lng), coord.lng - deltaLng(coord.lat, coord.lng), CoordSystem.WGS84)

fun wgs84ToGcj02(coord: Coord): Coord =
    if (outOfChina(coord.lat, coord.lng)) Coord(coord.lat, coord.lng, CoordSystem.GCJ02)
    else Coord(coord.lat + deltaLat(coord.lat, coord.lng), coord.lng + deltaLng(coord.lat, coord.lng), CoordSystem.GCJ02)

private fun gcj02ToWgs84Lat(lat: Double, lng: Double) =
    if (outOfChina(lat, lng)) lat else lat - deltaLat(lat, lng)

private fun gcj02ToWgs84Lng(lat: Double, lng: Double) =
    if (outOfChina(lat, lng)) lng else lng - deltaLng(lat, lng)

/** `h0.i` — outside this box the two datums are treated as identical. */
fun outOfChina(lat: Double, lng: Double): Boolean =
    lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

private fun deltaLat(lat: Double, lng: Double): Double {
    val dX = lng - 105.0
    val dY = lat - 35.0
    val rad = lat * PI / 180.0
    val sinRad = sin(rad)
    val cc = 1.0 - EE * sinRad * sinRad
    return transformLat(dX, dY) * 180.0 / (((A * (1.0 - EE)) / (cc * sqrt(cc))) * PI)
}

private fun deltaLng(lat: Double, lng: Double): Double {
    val dX = lng - 105.0
    val dY = lat - 35.0
    val rad = lat * PI / 180.0
    val sinRad = sin(rad)
    val cc = 1.0 - EE * sinRad * sinRad
    return transformLng(dX, dY) * 180.0 / (((A / sqrt(cc)) * cos(rad)) * PI)
}

private fun transformLat(x: Double, y: Double): Double {
    // `h0.j`, term by term: the first line is the polynomial, then three paired sine groups each scaled 2/3.
    var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
    ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
    ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
    ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y / 30.0 * PI)) * 2.0 / 3.0
    return ret
}

private fun transformLng(x: Double, y: Double): Double {
    // `h0.k`. `0.1*x*x` and `0.1*x*y` are that method's `d12 = x*0.1` multiplied by x and by y.
    var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
    ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
    ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
    ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
    return ret
}

/**
 * Great-circle distance in metres, the neutral stand-in for `DistanceUtil.getDistance`, which the
 * recovered app calls on bd09 coordinates (`x0.java:171`, `x0.java:241`).
 *
 * `null` where the original used its `-1.0` sentinel: `DistanceUtil` returns -1 when either point is
 * missing, and callers branch on it (`x0.java:786 dG != -1.0d`). Encoding that as a nullable instead of a
 * magic number keeps that branch readable.
 */
fun distanceMeters(from: Coord?, to: Coord?): Double? {
    if (from == null || to == null) return null
    if (from.isPlaceholder || to.isPlaceholder) return null
    val a1 = Math.toRadians(from.lat)
    val a2 = Math.toRadians(to.lat)
    val dLat = a2 - a1
    val dLng = Math.toRadians(to.lng - from.lng)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(a1) * cos(a2) * sin(dLng / 2) * sin(dLng / 2)
    return 2.0 * EARTH_RADIUS_METERS * kotlin.math.asin(kotlin.math.min(1.0, sqrt(h)))
}

/**
 * `h0.e` kept verbatim — spherical law of cosines. It loses centimetre precision at short range, which is
 * why the app measured with `DistanceUtil` and only used this for coarse checks; pinned by a golden value
 * so the port cannot drift.
 */
fun coarseDistanceMeters(from: Coord, to: Coord): Double {
    val rad1 = from.lat * PI / 180.0
    val rad2 = to.lat * PI / 180.0
    var cosTerm = cos(rad1) * cos(rad2) * cos((from.lng - to.lng) * PI / 180.0) + sin(rad1) * sin(rad2)
    if (cosTerm > 1.0) cosTerm = 1.0
    if (cosTerm < -1.0) cosTerm = -1.0
    return acos(cosTerm) * EARTH_RADIUS_METERS
}

private const val PI = 3.141592653589793
private const val X_PI = 52.35987755982988
private const val EE = 0.006693421622965943
private const val A = 6378245.0
private const val EARTH_RADIUS_METERS = 6371000.0
