package com.youloft.linkup.map.provider.amap

import android.content.Context
import android.os.Bundle
import android.view.View
import com.youloft.linkup.location.Coord
import com.youloft.linkup.location.wgs84ToGcj02
import com.youloft.linkup.map.MapLatLng
import com.youloft.linkup.map.StayMark
import com.youloft.linkup.map.TrackMap
import com.youloft.linkup.map.TrackMapProvider
import com.youloft.linkup.map.TrackOverlay

/**
 * The AMap map adapter — the second vendor behind [TrackMap], same role its location twin plays in
 * `:location`. **No fidelity claim**: the shipping app carried Baidu's map, so the rendering choices here
 * (zoom, weight, colour — [AmapMapSdk]'s constants) are this project's, and the vendor contract is the
 * official AMap map SDK, not a decompiled block.
 *
 * Datum rule, mirroring the Baidu adapter: the overlay arrives WGS84, AMap's canvas renders **GCJ02**, and
 * every coordinate converts on the way in.
 */
class AmapTrackMapProvider(
    private val sdk: AmapMapSdk = AmapMapSdk(),
) : TrackMapProvider {

    override val id = "amap"

    /** Classes present; the key rides in the manifest the future AAR ships with ([AmapMapSdk] doc). */
    override val isInstalled: Boolean
        get() = sdk.isPresent

    override fun create(context: Context): TrackMap = AmapTrackMap(context, sdk)
}

class AmapTrackMap(
    context: Context,
    private val sdk: AmapMapSdk,
) : TrackMap {

    private val appContext: Context = context.applicationContext
    private var mapView: Any? = null
    private var map: Any? = null

    override val view: View
        get() = mapView as? View ?: throw IllegalStateException("onCreate 前没有可挂载的地图视图")

    override val id = "amap"

    override fun onCreate(state: Bundle?) {
        if (mapView != null) return
        val created = sdk.createMapView(appContext) ?: return
        if (!sdk.onCreate(created, state)) return
        mapView = created
        map = sdk.getMap(created)
    }

    override fun onResume() {
        mapView?.let { sdk.onResume(it) }
    }

    override fun onPause() {
        mapView?.let { sdk.onPause(it) }
    }

    override fun onDestroy() {
        mapView?.let { sdk.onDestroy(it) }
        mapView = null
        map = null
    }

    override fun onSaveState(): Bundle? =
        mapView?.let { sdk.onSaveInstanceState(it) }

    override fun showTrack(overlay: TrackOverlay) {
        val map = map ?: return
        sdk.clear(map)
        // Every coordinate converts, path and pins alike: AMap's canvas renders GCJ02.
        val path = overlay.path.mapNotNull { point -> toGcj02(point) }
        if (path.isNotEmpty()) {
            sdk.polyline(path)?.let { sdk.addOverlay(map, it) }
            sdk.moveCamera(map, path.first(), AmapMapSdk.TRACK_ZOOM)
        }
        overlay.stays.forEach { mark ->
            toGcj02(mark.coord)?.let { latLng -> sdk.marker(latLng)?.let { sdk.addOverlay(map, it) } }
        }
    }

    override fun focus(coord: MapLatLng) {
        val map = map ?: return
        toGcj02(coord)?.let { sdk.moveCamera(map, it, AmapMapSdk.TRACK_ZOOM) }
    }

    /** WGS84 → GCJ02, the display conversion AMap's canvas needs. */
    private fun toGcj02(point: MapLatLng): Any? {
        val gcj02 = wgs84ToGcj02(Coord(point.lat, point.lng))
        return sdk.latLng(gcj02.lat, gcj02.lng)
    }
}
