package com.youloft.linkup.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amap.api.maps.CameraUpdate
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.youloft.linkup.map.provider.amap.AmapMapSdk
import com.youloft.linkup.map.provider.amap.AmapTrackMapProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The AMap map adapter against its doubles — the second vendor proving the seam, with the datum rule
 * pinned the same way Baidu's is: the overlay arrives WGS84, the canvas receives GCJ02, and the
 * conversion is the recovered one — `wgs84_to_gcj02(39.90469,116.40717) = 39.906093413,116.413412395`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmapTrackMapTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var sdk: InspectableSdk

    @Before
    fun setUp() {
        sdk = InspectableSdk()
    }

    @Test
    fun `no initializer gate for AMap maps - onCreate builds straight away`() {
        val map = newMap()
        map.onCreate(null)

        val view = assertNotNull(sdk.mapView, "the vendor view was built without an initializer call")
        assertEquals(1, view.createCount)
        assertEquals(view, map.view)
    }

    @Test
    fun `the path reaches the canvas in GCJ02, converted from the stored WGS84`() {
        val map = newMap()
        map.onCreate(null)
        val view = assertNotNull(sdk.mapView)

        map.showTrack(
            TrackOverlay(
                path = listOf(MapLatLng(39.90469, 116.40717), MapLatLng(39.90569, 116.40817)),
                stays = emptyList(),
            ),
        )

        val polyline = view.map.polylines.single()
        // geo_golden_156.txt: wgs84_to_gcj02(39.90469,116.40717) = 39.906093413,116.413412395
        assertEquals(39.906093413, polyline.points[0].latitude, 1e-8)
        assertEquals(116.413412395, polyline.points[0].longitude, 1e-8)
        assertEquals(AmapMapSdk.TRACK_LINE_WIDTH_DP.toFloat(), polyline.width, "AMap's width is dp, Baidu's px")
        assertEquals(AmapMapSdk.TRACK_LINE_COLOR, polyline.color)
        val move = assertNotNull(view.map.cameraMoves.single())
        assertEquals(39.906093413, move.target.latitude, 1e-8)
        assertEquals(AmapMapSdk.TRACK_ZOOM, move.zoom)
    }

    @Test
    fun `stay pins are markers, converted like the path`() {
        val map = newMap()
        map.onCreate(null)
        val view = assertNotNull(sdk.mapView)

        map.showTrack(
            TrackOverlay(
                path = listOf(MapLatLng(39.90469, 116.40717)),
                stays = listOf(StayMark(MapLatLng(39.90469, 116.40717), timePeriod = 1_700_000_000)),
            ),
        )

        val pin = view.map.markers.single()
        assertEquals(39.906093413, assertNotNull(pin.position).latitude, 1e-8, "same conversion as the path")
    }

    @Test
    fun `lifecycle forwards verbatim and calls before onCreate are absorbed`() {
        val map = newMap()
        map.onResume()
        map.showTrack(TrackOverlay(path = emptyList(), stays = emptyList()))
        assertNull(sdk.mapView, "nothing built yet; the adapter did nothing")

        map.onCreate(null)
        val view = assertNotNull(sdk.mapView)
        map.onResume()
        map.onPause()
        map.onDestroy()
        assertEquals(listOf(1, 1, 1), listOf(view.resumeCount, view.pauseCount, view.destroyCount))
    }

    @Test
    fun `a missing AAR reports not installed`() {
        val withoutAar = AmapTrackMapProvider(sdk = MissingSdk())
        assertFalse(withoutAar.isInstalled)
    }

    // ------------------------------------------------------------------ fixtures

    private fun newMap(): TrackMap {
        val provider = AmapTrackMapProvider(sdk = sdk)
        assertTrue(provider.isInstalled)
        return provider.create(context)
    }

    private class InspectableSdk : AmapMapSdk() {
        var mapView: TextureMapView? = null

        override fun createMapView(context: Context): Any? {
            val view = TextureMapView(context)
            mapView = view
            return view
        }
    }

    private class MissingSdk : AmapMapSdk() {
        override val isPresent: Boolean = false
        override val missingReason: String = "找不到 com.amap.api.maps.TextureMapView：本 build 未包含高德地图 AAR"
    }
}
