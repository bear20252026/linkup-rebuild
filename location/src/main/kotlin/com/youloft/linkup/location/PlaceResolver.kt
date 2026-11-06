package com.youloft.linkup.location

/**
 * Address ↔ coordinate resolution as a separate seam from [LocationProvider].
 *
 * The recovered app treats these as two different dependencies, and that is the reason this interface
 * exists at all: `com/youloft/watcher/utils/j0.java` (source name `GeoCodeUtils.kt`, from the R8 marker at
 * `j0.java:76`) drives `com.baidu.mapapi.search.geocode.GeoCoder` and is a *second* SDK from the same
 * vendor — a different artifact, a different initializer, and the only thing in the app that can turn an
 * address back into a coordinate. Meanwhile `BDLocation` carries an address inline whenever
 * `setIsNeedAddress(true)` was set, so the location SDK often needs no resolver at all.
 *
 * Which is why the app's rule reads as it does (`manager/x0.java:785-797`): if a fix has no address, reuse
 * the previous one when within 10 m, otherwise resolve — i.e. resolution is a *fallback*, not a pipeline
 * stage. An implementation over the framework `Geocoder` is therefore a drop-in for exactly that branch.
 *
 * Coordinates in and out are WGS84, like every other boundary of this module.
 */
interface PlaceResolver {
    /** False when no resolver SDK is present; callers then show coordinates only. */
    val isAvailable: Boolean

    /** Null when nothing could be resolved — matching `j0.java:67`, which fails on an *empty* address string. */
    suspend fun resolvePlace(coord: Coord): Place?

    /**
     * Address → coordinate, for "家/公司地址" style input (`j0.java:36-42` rejects a result whose latitude and
     * longitude are both zero). Null when the string cannot be located.
     */
    suspend fun resolveCoord(city: String, address: String): Coord?
}
