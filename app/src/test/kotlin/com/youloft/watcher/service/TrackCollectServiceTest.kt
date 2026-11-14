package com.youloft.watcher.service

import android.Manifest
import android.app.Application
import android.content.Intent
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.youloft.linkup.location.CoordSystem
import com.youloft.linkup.location.LocationCapabilities
import com.youloft.linkup.location.LocationFault
import com.youloft.linkup.location.LocationFailure
import com.youloft.linkup.location.LocationFix
import com.youloft.linkup.location.LocationListener
import com.youloft.linkup.location.LocationProvider
import com.youloft.linkup.location.LocationProviders
import com.youloft.linkup.location.LocationRequest
import com.youloft.linkup.location.LocationResult
import com.youloft.watcher.data.LinkUpApplication
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.data.local.InMemoryKeyValueStores
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The collection service, driven the way the controller drives it: real provider catalog (the framework
 * provider is the one that answers here — no vendor AAR on the test classpath), real classifier, real
 * gateway construction; only the stores are in-memory and the network is never reached, because no fix is
 * fed through the listener in these tests.
 *
 * The observable end of the loop is [LocationManager]: a started service must have registered framework
 * location updates, a stopped one must have released them, and the dormancy switch must be visible as a
 * changed request interval.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TrackCollectServiceTest.ServiceApp::class)
class TrackCollectServiceTest {

    private fun locationManager(): LocationManager =
        appGraph(ApplicationProvider.getApplicationContext())
            .getSystemService(LocationManager::class.java)!!

    /** Robolectric denies everything by default; the framework provider refuses without these. */
    @Before
    fun grantLocationPermission() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
    }

    @Test
    fun `a start command brings up the collector and registers framework updates`() {
        val controller = Robolectric.buildService(TrackCollectService::class.java, startIntent())
        val service = controller.create().get()

        service.onStartCommand(startIntent(), 0, 1)

        assertTrue(TrackCollectService.running, "the provider accepted the follow request")
        assertTrue(
            requestIntervals().isNotEmpty(),
            "the framework provider really registered location updates",
        )
        assertEquals(service, TrackCollectService.instance)

        controller.destroy()
        assertFalse(TrackCollectService.running)
        assertEquals(
            emptyList(),
            requestIntervals(),
            "teardown releases the framework registrations, not just the flag",
        )
    }

    @Test
    fun `backgrounding swaps the registered request to the dormant interval and back`() {
        val controller = Robolectric.buildService(TrackCollectService::class.java, startIntent())
        val service = controller.create().get()
        service.onStartCommand(startIntent(), 0, 1)

        service.setBackgrounded(true)
        val dormantIntervals = requestIntervals()
        assertTrue(
            dormantIntervals.all { it == 30_000L },
            "s0.java:810 -> Battery_Saving + locationScanMillsWhenDormancy; actual=$dormantIntervals",
        )

        service.setBackgrounded(false)
        val foregroundIntervals = requestIntervals()
        assertTrue(foregroundIntervals.all { it == 2_000L }, "back to the foreground loop; actual=$foregroundIntervals")

        controller.destroy()
    }

    @Test
    @Config(application = TrackCollectServiceTest.NoProviderApp::class)
    fun `no usable provider stops the service instead of guarding nothing`() {
        val controller = Robolectric.buildService(TrackCollectService::class.java, startIntent())
        val service = controller.create().get()

        service.onStartCommand(startIntent(), 0, 1)

        assertFalse(
            TrackCollectService.running,
            "a foreground notification that guards no sensor is a lie, so the service walked away",
        )
        controller.destroy()
    }

    // ------------------------------------------------------------------ helpers

    private fun startIntent(): Intent = Intent().setAction(TrackCollectService.ACTION_START)

    /**
     * Framework sampling intervals currently registered by the provider, across all its sources. The
     * passive source never polls, so its shadow answers [Long.MAX_VALUE] — a sentinel, not an interval —
     * and is filtered out rather than asserted against.
     */
    private fun requestIntervals(): List<Long> {
        val manager = locationManager()
        return manager.allProviders
            .flatMap { provider ->
                shadowOf(manager).getLegacyLocationRequests(provider).map { it.intervalMillis }
            }
            .filter { it != Long.MAX_VALUE }
    }

    @Test
    fun `the production start never lets a refused FGS launch escape`() {
        // 登录不闪退（审计⑤）：Android 12+ 在后台冷启动拒绝 startForegroundService 时，包装要把它
        // 吃掉——采集晚一轮再起，调用方（登录链）不能被压崩。Robolectric 允许启动；这条钉住的是
        // "refresh 全程不抛"，真机上的拒绝路径由同一个 runCatching 收口。
        val controller = CollectController.forProduction(
            ApplicationProvider.getApplicationContext(),
            CollectController.Gates({ true }, { true }, { true }),
        )

        controller.refresh()
    }

    /** In-memory stores only; the real catalog runs, and the framework provider wins it. */
    class ServiceApp : LinkUpApplication() {
        override val keyValueStores = InMemoryKeyValueStores()
    }

    /** The catalog a build without any location source would report — every candidate refuses. */
    class NoProviderApp : LinkUpApplication() {
        override val keyValueStores = InMemoryKeyValueStores()

        override val locationProviders: LocationProviders = LocationProviders(listOf(RefusingProvider))
    }

    private object RefusingProvider : LocationProvider {
        override val id = "none"
        override val isInstalled = false
        override val capabilities = LocationCapabilities(
            nativeCoordSystem = CoordSystem.WGS84,
            inlinePlace = false,
            poiList = false,
            needsVendorKey = false,
            supportsRuntimeInterval = false,
        )

        override fun start(request: LocationRequest, listener: LocationListener) = false

        override fun update(request: LocationRequest) = false

        override fun stop() = Unit

        override suspend fun singleFix(request: LocationRequest): LocationResult =
            LocationResult.Failed(LocationFault(LocationFailure.SERVICE_UNAVAILABLE))

        override fun lastFix(): LocationFix? = null

        override fun close() = Unit
    }
}
