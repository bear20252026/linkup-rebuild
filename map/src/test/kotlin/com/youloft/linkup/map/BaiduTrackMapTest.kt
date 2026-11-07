package com.youloft.linkup.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baidu.mapapi.SDKInitializer
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.map.TextureMapView
import com.youloft.linkup.map.provider.baidu.BaiduMapSdk
import com.youloft.linkup.map.provider.baidu.BaiduTrackMapProvider
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
 * Runs the real reflective Baidu map adapter against the SDK-shaped doubles, pinning the datum rule the
 * whole file exists for: the overlay arrives WGS84, the canvas receives BD09, and the conversion is the
 * recovered one — `wgs84_to_bd09(39.90469,116.40717) = 39.912357980,116.419815071` from
 * `recovered/geo_golden_156.txt`. A skipped conversion is a half-kilometre error nobody sees in code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BaiduTrackMapTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var sdk: InspectableSdk

    @Before
    fun setUp() {
        SDKInitializer.resetState()
        sdk = InspectableSdk()
    }

    @Test
    fun `onCreate runs the initializer gate once, then builds the map view`() {
        val map = newMap()
        map.onCreate(null)

        assertEquals(true, SDKInitializer.agreedPrivacy, "the SDK's own privacy gate runs before anything else")
        assertEquals(1, SDKInitializer.initializeCount, "initialize is once per process")
        val view = assertNotNull(sdk.mapView, "the vendor view was built")
        assertEquals(1, view.createCount)
        assertEquals(view, map.view, "the interface hands back the vendor view for the container")

        map.onCreate(null)
        assertEquals(1, view.createCount, "a second onCreate is a no-op, not a rebuild")
    }

    @Test
    fun `the initializer is refused without consent and the map stays unbuilt`() {
        val refused = BaiduTrackMapProvider(privacyAgreed = { false }, sdk = sdk)
        assertFalse(refused.isInstalled)

        val map = refused.create(context)
        map.onCreate(null)

        assertNull(sdk.mapView, "no consent, no map view — the screen keeps its mask")
        assertEquals(0, SDKInitializer.initializeCount)
    }

    @Test
    fun `a missing AAR reports not installed`() {
        val withoutAar = BaiduTrackMapProvider(privacyAgreed = { true }, sdk = MissingSdk())
        assertFalse(withoutAar.isInstalled)
    }

    @Test
    fun `lifecycle forwards verbatim`() {
        val map = newMap()
        map.onCreate(null)
        val view = assertNotNull(sdk.mapView)

        map.onResume()
        map.onPause()
        map.onDestroy()
        assertEquals(listOf(1, 1, 1), listOf(view.resumeCount, view.pauseCount, view.destroyCount))
    }

    @Test
    fun `the path reaches the canvas in BD09, converted from the stored WGS84`() {
        val map = newMap()
        map.onCreate(null)
        val view = assertNotNull(sdk.mapView)

        map.showTrack(
            TrackOverlay(
                path = listOf(MapLatLng(39.90469, 116.40717), MapLatLng(39.90569, 116.40817)),
                stays = emptyList(),
            ),
        )

        val polyline = view.map.added.filterIsInstance<PolylineOptions>().single()
        // geo_golden_156.txt: wgs84_to_bd09(39.90469,116.40717) = 39.912357980,116.419815071
        assertEquals(39.912357980, polyline.points[0].latitude, 1e-8)
        assertEquals(116.419815071, polyline.points[0].longitude, 1e-8)
        assertEquals(BaiduMapSdk.TRACK_LINE_WIDTH_PX, polyline.width)
        assertEquals(BaiduMapSdk.TRACK_LINE_COLOR, polyline.color)
        // The camera opens on the walked start, at the track-viewing zoom.
        assertEquals(39.912357980, assertNotNull(view.map.statuses.single()).target.latitude, 1e-8)
        assertEquals(BaiduMapSdk.TRACK_ZOOM, view.map.statuses.single().zoom)
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

        val pin = view.map.added.filterIsInstance<MarkerOptions>().single()
        assertEquals(39.912357980, assertNotNull(pin.position).latitude, 1e-8, "same conversion as the path")
    }

    @Test
    fun `an empty overlay clears and draws nothing`() {
        val map = newMap()
        map.onCreate(null)
        val view = assertNotNull(sdk.mapView)
        map.showTrack(TrackOverlay(path = listOf(MapLatLng(39.90469, 116.40717)), stays = emptyList()))

        map.showTrack(TrackOverlay(path = emptyList(), stays = emptyList()))

        assertTrue(view.map.added.filterIsInstance<PolylineOptions>().isEmpty())
        assertEquals(2, view.map.clearCount, "once per showTrack call: every draw starts from a clean canvas")
    }

    @Test
    fun `calls before onCreate are absorbed, not crashes`() {
        val map = newMap()
        map.showTrack(TrackOverlay(path = listOf(MapLatLng(39.90469, 116.40717)), stays = emptyList()))
        map.focus(MapLatLng(39.90469, 116.40717))
        map.onResume()
        assertNull(sdk.mapView, "still nothing built; the adapter just did nothing")
    }

    // ------------------------------------------------------------------ fixtures

    private fun newMap(): TrackMap {
        val provider = BaiduTrackMapProvider(privacyAgreed = { true }, sdk = sdk)
        assertTrue(provider.isInstalled)
        return provider.create(context)
    }

    /** Injects the double; changes no binding semantics. */
    private class InspectableSdk : BaiduMapSdk() {
        var mapView: TextureMapView? = null

        override fun createMapView(context: Context): Any? {
            val view = TextureMapView(context)
            mapView = view
            return view
        }
    }

    private class MissingSdk : BaiduMapSdk() {
        override val isPresent: Boolean = false
        override val missingReason: String = "找不到 com.baidu.mapapi.map.TextureMapView：本 build 未包含百度地图 AAR"
    }
}
