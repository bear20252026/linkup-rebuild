package com.youloft.linkup.map.provider.osm

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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * The OSM map adapter — the vendor-free basemap behind [TrackMap]. osmdroid is an open-source library on
 * Maven Central (no key, no vendor contract, always on the classpath), so unlike the Baidu/AMap twins this
 * adapter binds **directly**, not by reflection, and `isInstalled` is unconditional.
 *
 * Datum rule, mirroring the vendor adapters: the overlay arrives **WGS84**, the canvas draws **GCJ02** —
 * the AMap road tiles this adapter serves are rendered in GCJ02, so every coordinate converts on the way
 * in with the same recovered conversion the other adapters use.
 *
 * Tile source: AMap's public web road tiles (`style=8`), this project's choice for a China basemap with
 * Chinese labels — written down in DESIGN; tile alignment and label quality are device-verify items
 * (待验 85). The URL builder is a pure function so the JVM can pin it.
 */
class OsmTrackMapProvider : TrackMapProvider {

    override val id = "osm"

    /** osmdroid rides the Gradle classpath — there is no AAR to miss and no key to lose. */
    override val isInstalled: Boolean
        get() = true

    override fun create(context: Context): TrackMap = OsmTrackMap(context)
}

class OsmTrackMap(context: Context) : TrackMap {

    private val appContext: Context = context.applicationContext
    private var mapView: MapView? = null

    /** The track's own overlays — the tiles overlay lives in the same list and must survive a redraw. */
    private val trackOverlays = mutableListOf<Overlay>()

    override val view: View
        get() = mapView ?: throw IllegalStateException("onCreate 前没有可挂载的地图视图")

    override val id = "osm"

    override fun onCreate(state: Bundle?) {
        if (mapView != null) return
        // osmdroid 的全局配置先于第一个 MapView：UA 换成自己的包名（瓦片服务器拒绝匿名批量抓取），
        // 缓存放私有 cacheDir——不申请外部存储。
        val config = Configuration.getInstance()
        config.userAgentValue = appContext.packageName
        config.osmdroidBasePath = File(appContext.cacheDir, "osmdroid")
        config.osmdroidTileCache = File(config.osmdroidBasePath, "tiles")

        val created = MapView(appContext)
        created.setTileSource(AmapRoadTileSource())
        created.setMultiTouchControls(true)
        created.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        created.minZoomLevel = AmapRoadTileSource.MIN_ZOOM.toDouble()
        created.maxZoomLevel = AmapRoadTileSource.MAX_ZOOM.toDouble()
        mapView = created
    }

    override fun onResume() {
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
    }

    override fun onDestroy() {
        mapView?.onDetach()
        mapView = null
        trackOverlays.clear()
    }

    /** osmdroid carries no Bundle state; the tiles come back from cache/network on their own. */
    override fun onSaveState(): Bundle? = null

    override fun showTrack(overlay: TrackOverlay) {
        val map = mapView ?: return
        // 只摘自己加的 overlay——索引 0 的瓦片层摘了底图就没了。
        map.overlays.removeAll(trackOverlays)
        trackOverlays.clear()
        val path = overlay.path.map { point -> toGcj02(point) }
        if (path.isNotEmpty()) {
            val line = Polyline(map)
            line.setPoints(path)
            val density = appContext.resources.displayMetrics.density
            line.outlinePaint.color = OsmMapSdk.TRACK_LINE_COLOR
            line.outlinePaint.strokeWidth = OsmMapSdk.TRACK_LINE_WIDTH_DP * density
            map.overlays.add(line)
            trackOverlays.add(line)
            map.controller.setZoom(OsmMapSdk.TRACK_ZOOM.toDouble())
            map.controller.setCenter(path.first())
        }
        overlay.stays.forEach { mark ->
            toGcj02(mark.coord)?.let { point ->
                val pin = Marker(map)
                pin.position = point
                pin.title = OsmMapSdk.stayLabel(mark.timePeriod)
                map.overlays.add(pin)
                trackOverlays.add(pin)
            }
        }
        map.invalidate()
    }

    override fun focus(coord: MapLatLng) {
        val map = mapView ?: return
        toGcj02(coord)?.let { point ->
            map.controller.setZoom(OsmMapSdk.TRACK_ZOOM.toDouble())
            map.controller.setCenter(point)
        }
    }

    /** WGS84 → GCJ02, the display conversion the AMap web tiles need. */
    private fun toGcj02(point: MapLatLng): GeoPoint {
        val gcj02 = wgs84ToGcj02(Coord(point.lat, point.lng))
        return GeoPoint(gcj02.lat, gcj02.lng)
    }
}

/**
 * osmdroid 的常量与瓦片源。渲染取值与 vendor 适配器对齐（同一轨迹在两家底图上应是同一条线）；
 * 瓦片源是高德公开道路瓦片——URL 是查询串形态，osmdroid 的默认拼接拼不出来，所以覆写
 * [getTileURLString]，纯函数的 [tileUrl] 给 JVM 钉住。
 */
object OsmMapSdk {

    const val TRACK_ZOOM = 14.0
    const val TRACK_LINE_WIDTH_DP = 8
    const val TRACK_LINE_COLOR = 0xFF2C7BE5.toInt()

    /** `tracks[].type == 2` 的停留点标注——原版画 pin 不带字，标注是本工程选择。 */
    fun stayLabel(timePeriod: Long): String = "停留 ${timePeriod}"
}

class AmapRoadTileSource : OnlineTileSourceBase(
    "AmapRoad",
    MIN_ZOOM,
    MAX_ZOOM,
    TILE_SIZE,
    ".png",
    BASE_URLS,
) {
    override fun getTileURLString(pMapTileIndex: Long): String = tileUrl(
        zoom = MapTileIndex.getZoom(pMapTileIndex),
        x = MapTileIndex.getX(pMapTileIndex),
        y = MapTileIndex.getY(pMapTileIndex),
    )

    companion object {
        const val MIN_ZOOM = 3
        const val MAX_ZOOM = 19
        const val TILE_SIZE = 256

        val BASE_URLS = arrayOf(
            "https://webrd01.is.autonavi.com",
            "https://webrd02.is.autonavi.com",
            "https://webrd03.is.autonavi.com",
            "https://webrd04.is.autonavi.com",
        )

        /** 四个子域轮转（x+y 取模）+ 查询串形态的道路瓦片（style=8）。 */
        fun tileUrl(zoom: Int, x: Int, y: Int): String =
            BASE_URLS[(x + y) % BASE_URLS.size] +
                "/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=$x&y=$y&z=$zoom"
    }
}
