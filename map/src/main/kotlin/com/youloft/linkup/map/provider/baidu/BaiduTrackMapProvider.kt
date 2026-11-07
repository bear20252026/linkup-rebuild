package com.youloft.linkup.map.provider.baidu

import android.content.Context
import android.os.Bundle
import android.view.View
import com.youloft.linkup.location.Coord
import com.youloft.linkup.location.wgs84ToBd09
import com.youloft.linkup.map.MapLatLng
import com.youloft.linkup.map.StayMark
import com.youloft.linkup.map.TrackMap
import com.youloft.linkup.map.TrackMapProvider
import com.youloft.linkup.map.TrackOverlay

/**
 * The Baidu map adapter — the vendor whose map the shipping app carried, reached without a compile-time
 * dependency because no copy of the map AAR survived the loss either.
 *
 * One datum rule carries this whole file: the overlay arrives in WGS84 ([TrackOverlay]), Baidu's canvas
 * renders **BD09**, and every coordinate goes through [wgs84ToBd09] on the way in — the same conversion the
 * original ran for map display (`h0.m`, see the `:location` golden file). A build that skipped it would
 * draw the walked path ~500 m from where the stay pins say it happened.
 *
 * No map in the recovered track page ever drew, so there is no decompiled rendering block: the line
 * weight, colour and zoom are this project's choices, marked as such in [BaiduMapSdk]. What *is* the
 * vendor's own contract — the initializer gate, the lifecycle methods, the overlay calls — is pinned by
 * the test doubles.
 */
class BaiduTrackMapProvider(
    private val privacyAgreed: () -> Boolean = { true },
    private val sdk: BaiduMapSdk = BaiduMapSdk(),
) : TrackMapProvider {

    override val id = "baidu"

    /** The AAR on the classpath plus the consent the initializer demands before it will run. */
    override val isInstalled: Boolean
        get() = sdk.isPresent && privacyAgreed()

    override fun create(context: Context): TrackMap = BaiduTrackMap(context, sdk, privacyAgreed)
}

class BaiduTrackMap(
    context: Context,
    private val sdk: BaiduMapSdk,
    private val privacyAgreed: () -> Boolean,
) : TrackMap {

    private val appContext: Context = context.applicationContext

    /** Built in [onCreate], after the initializer has run; null when the SDK refused. */
    private var mapView: Any? = null
    private var map: Any? = null

    override val view: View
        get() = mapView as? View ?: throw IllegalStateException("onCreate 前没有可挂载的地图视图")

    override val id = "baidu"

    override fun onCreate(state: Bundle?) {
        if (mapView != null) return
        if (!sdk.ensureInitialized(appContext, privacyAgreed())) return
        val created = sdk.createMapView(appContext) ?: return
        if (!sdk.onCreate(created, appContext, state)) return
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
        // Every coordinate converts, path and pins alike: Baidu's canvas renders BD09.
        val path = overlay.path.mapNotNull { point ->
            toBd09(StayMark(point, 0L))
        }
        if (path.isNotEmpty()) {
            sdk.polyline(path)?.let { sdk.addOverlay(map, it) }
            // The camera follows the walked start, so a track always opens looking at where it began.
            sdk.moveCamera(map, path.first(), BaiduMapSdk.TRACK_ZOOM)
        }
        overlay.stays.forEach { mark ->
            toBd09(mark)?.let { latLng -> sdk.marker(latLng)?.let { sdk.addOverlay(map, it) } }
        }
    }

    override fun focus(coord: MapLatLng) {
        val map = map ?: return
        toBd09(StayMark(coord, 0L))?.let { sdk.moveCamera(map, it, BaiduMapSdk.TRACK_ZOOM) }
    }

    /** WGS84 → BD09, the display conversion the original's own map path ran. */
    private fun toBd09(mark: StayMark): Any? {
        val bd09 = wgs84ToBd09(Coord(mark.coord.lat, mark.coord.lng))
        return sdk.latLng(bd09.lat, bd09.lng)
    }
}
