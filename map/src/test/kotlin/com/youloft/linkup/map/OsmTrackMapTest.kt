package com.youloft.linkup.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.youloft.linkup.map.provider.osm.AmapRoadTileSource
import com.youloft.linkup.map.provider.osm.OsmMapSdk
import com.youloft.linkup.map.provider.osm.OsmTrackMapProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * The OSM map adapter against the **real** osmdroid classes — the third provider is not vendor-bound, so
 * the tests run production code on production types instead of stand-ins. The datum rule is pinned the
 * same way AMap's is: the overlay arrives WGS84, the canvas (AMap road tiles) draws GCJ02, and the
 * conversion is the recovered one — `wgs84_to_gcj02(39.90469,116.40717) = 39.906093413,116.413412395`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsmTrackMapTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the provider is always installed and ids itself osm`() {
        val provider = OsmTrackMapProvider()
        assertTrue(provider.isInstalled, "osmdroid rides the classpath; there is no AAR to miss")
        assertEquals("osm", provider.id)
    }

    @Test
    fun `onCreate builds the view and absorbs calls before it`() {
        val map = newMap()
        map.onResume()
        map.showTrack(TrackOverlay(path = emptyList(), stays = emptyList()))
        assertNull(map.onSaveState(), "osmdroid carries no Bundle state")

        map.onCreate(null)
        assertEquals(map.view, viewOf(map), "the view is the real osmdroid MapView")
        assertEquals("osm", map.id)
    }

    @Test
    fun `the path reaches the canvas in GCJ02, converted from the stored WGS84`() {
        val map = newMap()
        map.onCreate(null)
        val view = viewOf(map)

        map.showTrack(
            TrackOverlay(
                path = listOf(MapLatLng(39.90469, 116.40717), MapLatLng(39.90569, 116.40817)),
                stays = emptyList(),
            ),
        )

        val polyline = view.overlays.filterIsInstance<Polyline>().single()
        // geo_golden_156.txt: wgs84_to_gcj02(39.90469,116.40717) = 39.906093413,116.413412395
        assertEquals(39.906093413, polyline.points[0].latitude, 1e-8)
        assertEquals(116.413412395, polyline.points[0].longitude, 1e-8)
        assertEquals(OsmMapSdk.TRACK_LINE_COLOR, polyline.outlinePaint.color)
        assertEquals(
            OsmMapSdk.TRACK_LINE_WIDTH_DP.toFloat(),
            polyline.outlinePaint.strokeWidth,
            "Robolectric density is 1.0, so dp lands as px one-to-one",
        )
        assertEquals(OsmMapSdk.TRACK_ZOOM, view.zoomLevel.toDouble(), 1e-6, "the camera follows the track")
        // Robolectric 的 MapView 未经过布局，getMapCenter() 走投影算出 (0,0)；手动 layout 给它尺寸后，
        // 从画布中心反查投影落点——setCenter 写进 expectedCenter，投影就是从它算的。
        view.layout(0, 0, 1080, 1920)
        val center = view.projection.fromPixels(540, 960)
        // 容差 1e-4°≈11m：投影反查带瓦片像素舍入（z14 一像素 ≈9.5m，实测偏 2.4m）；
        // GCJ 偏移本身 ~500m≈0.0045°，是容差的 45 倍——坐标基准错了这里必炸。
        assertEquals(
            39.906093413,
            center.latitude,
            1e-4,
            "the canvas center is the converted first point, aligned with the GCJ02 tiles",
        )
    }

    @Test
    fun `stay pins are markers, converted like the path`() {
        val map = newMap()
        map.onCreate(null)
        val view = viewOf(map)

        map.showTrack(
            TrackOverlay(
                path = listOf(MapLatLng(39.90469, 116.40717)),
                stays = listOf(StayMark(MapLatLng(39.90469, 116.40717), timePeriod = 1_700_000_000)),
            ),
        )

        val pin = view.overlays.filterIsInstance<Marker>().single()
        assertEquals(39.906093413, assertNotNull(pin.position).latitude, 1e-8, "same conversion as the path")
    }

    @Test
    fun `a redraw replaces the previous overlays and the basemap survives`() {
        val map = newMap()
        map.onCreate(null)
        val view = viewOf(map)
        val track = TrackOverlay(
            path = listOf(MapLatLng(39.90469, 116.40717)),
            stays = listOf(StayMark(MapLatLng(39.90469, 116.40717), timePeriod = 1L)),
        )

        map.showTrack(track)
        map.showTrack(track)

        assertEquals(1, view.overlays.filterIsInstance<Polyline>().count(), "the old line is gone")
        assertEquals(1, view.overlays.filterIsInstance<Marker>().count(), "the old pin is gone")
        assertTrue(
            view.tileProvider !== null && view.overlays.isNotEmpty(),
            "the tiles overlay survived the redraw",
        )
    }

    @Test
    fun `an empty overlay clears the drawn track`() {
        val map = newMap()
        map.onCreate(null)
        val view = viewOf(map)
        map.showTrack(
            TrackOverlay(path = listOf(MapLatLng(39.90469, 116.40717)), stays = emptyList()),
        )

        map.showTrack(TrackOverlay(path = emptyList(), stays = emptyList()))

        assertEquals(
            emptyList<Polyline>(),
            view.overlays.filterIsInstance<Polyline>(),
            "an empty overlay is the clear path, not a no-op",
        )
    }

    @Test
    fun `lifecycle forwards and calls after destroy are absorbed`() {
        val map = newMap()
        map.onCreate(null)
        val view = viewOf(map)
        map.onResume()
        map.onPause()
        map.onDestroy()
        // onDetach 后适配器不再持有视图：之后的任何调用都落进"没有地图"分支，不炸。
        map.onResume()
        map.showTrack(TrackOverlay(path = listOf(MapLatLng(39.90469, 116.40717)), stays = emptyList()))
        map.focus(MapLatLng(39.90469, 116.40717))
        assertEquals(0, view.overlays.filterIsInstance<Polyline>().count(), "the detached map drew nothing")
    }

    @Test
    fun `the tile url builder pins the amap road tile shape`() {
        assertEquals(
            "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=13382&y=6722&z=14",
            AmapRoadTileSource.tileUrl(zoom = 14, x = 13_382, y = 6_722),
        )
        // 四个子域按 (x+y) mod 4 轮转：换一个坐标块换一个域名。
        assertTrue(
            AmapRoadTileSource.tileUrl(zoom = 3, x = 1, y = 1).startsWith("https://webrd03.is.autonavi.com"),
            "(1+1) mod 4 = 2 → the third subdomain",
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun newMap(): TrackMap {
        val provider = OsmTrackMapProvider()
        assertTrue(provider.isInstalled)
        return provider.create(context)
    }

    private fun viewOf(map: TrackMap): MapView = map.view as MapView
}
