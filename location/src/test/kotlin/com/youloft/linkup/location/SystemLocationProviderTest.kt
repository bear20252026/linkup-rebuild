package com.youloft.linkup.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.youloft.linkup.location.provider.system.LocationPermissions
import com.youloft.linkup.location.provider.system.SystemLocationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLocationManager

/**
 * The framework provider is the one that works in this build today, so this is also the only place where the
 * layer's assumptions about Android itself get checked:
 *
 *  * permission is the layer's own gate ([LocationPermissions]), injected so all three answers are testable;
 *  * `Location.getSpeed()` is already metres per second -- dividing it by 3.6 the way the Baidu adapter has
 *    to would change the reporting cadence by 3.6x (`manager/x0.java:288`);
 *  * an `android.location.Location` carries no address and no POI, so `place` stays null and the caller has
 *    to reach for a [com.youloft.linkup.location.PlaceResolver].
 *
 * Machine facts established against Robolectric 4.11.1 / SDK 34, which the assertions below rely on:
 * `allProviders` is `[gps, passive, network]` with gps enabled and network disabled by default;
 * `requestLocationUpdates(provider, every, distance, listener, looper)` does NOT throw `SecurityException`
 * for an ungranted permission; and `ShadowLocationManager.simulateLocation` really does deliver to a
 * registered listener once the main looper is idled -- so a fix is pushed through the adapter, not just the
 * registration path. `getLegacyLocationRequests(provider).intervalMillis` is how an interval or a leftover
 * registration is read back. Every test but the one-shot leak test pins the enabled set to `gps` alone so
 * the listener counts are meaningful.
 *
 * Two behaviours this file re-checks on the success path: a fix carries the platform provider's own name
 * (`gps` / `network`) in `providerId` rather than this layer's id, and the one-shot framework registration a
 * successful `singleFix` adds is removed again -- both were defects this suite caught, and both are asserted
 * below rather than documented here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemLocationProviderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var manager: LocationManager
    private lateinit var shadow: ShadowLocationManager
    private val recorder = Recorder()

    @Before
    fun setUp() {
        manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadow = shadowOf(manager)
        manager.allProviders.forEach { shadow.setProviderEnabled(it, false) }
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    private fun provider(permission: LocationPermission) =
        SystemLocationProvider(context, permissions = FixedPermissions(permission))

    private fun gpsRequests() = shadow.getLegacyLocationRequests(LocationManager.GPS_PROVIDER)

    private fun networkRequests() = shadow.getLegacyLocationRequests(LocationManager.NETWORK_PROVIDER)

    // ------------------------------------------------------------------ permission gate

    @Test
    fun `a denied device cannot start and says NO_PERMISSION`() {
        val denied = provider(LocationPermission.DENIED)

        assertFalse(denied.start(LocationRequest.FOREGROUND, recorder))
        assertEquals(0, shadow.requestLocationUpdateListeners.size, "nothing may be registered behind the gate")
        assertTrue(recorder.fixes.isEmpty())

        val fault = recorder.faults.single()
        assertEquals(LocationFailure.NO_PERMISSION, fault.reason)
        assertEquals("DENIED", fault.detail, "the enum name is what the status screen greps for")
    }

    @Test
    fun `coarse-only permission is enough to follow`() {
        val coarse = provider(LocationPermission.GRANTED_COARSE_ONLY)

        assertTrue(coarse.start(LocationRequest.FOREGROUND, recorder))
        assertTrue(recorder.faults.isEmpty(), "coarse-only is a degraded fix, not a refusal")
        assertEquals(1, shadow.requestLocationUpdateListeners.size)
    }

    @Test
    fun `fine permission starts and records the requested interval`() {
        val fine = provider(LocationPermission.GRANTED_FINE)

        assertTrue(fine.start(LocationRequest(intervalMillis = 7_500), recorder))
        val request = gpsRequests().single()
        assertEquals(7_500L, request.intervalMillis, "requestLocationUpdates carried the neutral interval")
        assertEquals(0f, request.minUpdateDistanceMeters, "the layer does not filter by distance")
        assertFalse(request.isSingleShot)
    }

    @Test
    fun `canLocate is the only permission rule the layer applies`() {
        assertTrue(LocationPermission.GRANTED_FINE.canLocate())
        assertTrue(LocationPermission.GRANTED_COARSE_ONLY.canLocate())
        assertFalse(LocationPermission.DENIED.canLocate())
    }

    // ------------------------------------------------------------------ installed / capabilities

    @Test
    fun `the framework provider is installed under Robolectric and advertises no inline address`() {
        val system = provider(LocationPermission.GRANTED_FINE)

        assertTrue(system.isInstalled, "getSystemService(LOCATION_SERVICE) resolves, so this is not a stub")
        assertEquals("system", system.id)
        val capabilities = system.capabilities
        assertEquals(CoordSystem.WGS84, capabilities.nativeCoordSystem)
        assertFalse(capabilities.inlinePlace, "android.location.Location carries no address")
        assertFalse(capabilities.poiList, "nothing in the framework fills Place.poiName")
        assertFalse(capabilities.needsVendorKey)
        assertTrue(capabilities.supportsRuntimeInterval, "re-registering the same listener is cheap")
    }

    // ------------------------------------------------------------------ fix delivery

    @Test
    fun `a delivered framework fix passes through unchanged`() {
        val system = provider(LocationPermission.GRANTED_FINE)
        assertTrue(system.start(LocationRequest.FOREGROUND, recorder))

        val raw = frameworkLocation(
            providerName = LocationManager.GPS_PROVIDER,
            latitude = 31.23037,
            longitude = 121.47370,
            accuracy = 8f,
            speed = 2.5f,
            bearing = 90f,
            timeMillis = 1_700_000_000_000L,
        )
        shadow.simulateLocation(raw)
        shadowOf(Looper.getMainLooper()).idle()

        val fix = recorder.fixes.single()
        assertEquals(31.23037, fix.coord.lat, COORD_TOLERANCE, "the framework already speaks WGS84")
        assertEquals(121.47370, fix.coord.lng, COORD_TOLERANCE)
        assertEquals(CoordSystem.WGS84, fix.coord.system)
        assertEquals(8f, fix.accuracyMeters)
        assertEquals(
            2.5f,
            fix.speedMetersPerSecond,
            "Location.getSpeed() is metres per second; the /3.6 the Baidu path needs would make this 3.6x " +
                "wrong and change the reporting cadence with it",
        )
        assertEquals(90f, fix.bearingDegrees)
        assertEquals(1_700_000_000L, fix.epochSeconds, "time / 1000, as the recovered bean did")
        assertNull(fix.place, "no inline address from the framework: the caller needs a PlaceResolver")
        assertNull(fix.vendorFixType)
        // Which platform provider produced the fix is part of the track row, so toNeutralFix() records
        // `this.provider ?: id` (SystemLocationProvider.kt:173): the framework names a GPS fix "gps", and
        // only a fix that carries no name at all falls back to this provider's own id. Reading
        // Location.getProvider() rather than assuming the single-source setup is what lets the server tell a
        // GNSS track from a network one.
        assertEquals("gps", fix.providerId)
        assertEquals(fix, system.lastFix())
        assertTrue(recorder.faults.isEmpty())
    }

    @Test
    fun `a framework fix with no speed or bearing still maps`() {
        val system = provider(LocationPermission.GRANTED_FINE)
        assertTrue(system.start(LocationRequest.FOREGROUND, recorder))

        val raw = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 39.90469
            longitude = 116.40717
            accuracy = 12f
            time = 1_700_000_001_000L
            // speed and bearing left at the framework defaults: hasSpeed() is false for an unset 0, and
            // bearing stays NaN, which hasBearing() reports as no bearing at all.
        }
        assertFalse(raw.hasSpeed(), "Robolectric runs the real android.location.Location here")
        shadow.simulateLocation(raw)
        shadowOf(Looper.getMainLooper()).idle()

        val fix = recorder.fixes.single()
        assertEquals(0f, fix.speedMetersPerSecond, "an unset speed maps to stopped, not to null")
        assertNull(fix.bearingDegrees, "an unset bearing is no bearing, not 0 degrees north")
        assertEquals(12f, fix.accuracyMeters)
        assertEquals(1_700_000_001L, fix.epochSeconds)
    }

    @Test
    fun `a disabled location service is reported as unavailable, not as a permission problem`() {
        manager.allProviders.forEach { shadow.setProviderEnabled(it, false) }
        val system = provider(LocationPermission.GRANTED_FINE)

        assertFalse(system.start(LocationRequest.FOREGROUND, recorder))
        val fault = recorder.faults.single()
        assertEquals(LocationFailure.SERVICE_UNAVAILABLE, fault.reason)
        assertTrue(assertNotNull(fault.detail).contains("位置服务全部关闭"), fault.detail ?: "")
    }

    // ------------------------------------------------------------------ update / stop

    @Test
    fun `update re-registers at the new interval and ignores an identical request`() {
        val system = provider(LocationPermission.GRANTED_FINE)
        assertTrue(system.start(LocationRequest.FOREGROUND, recorder))
        assertEquals(2_000L, gpsRequests().single().intervalMillis)
        val listeners = shadow.requestLocationUpdateListeners.size

        assertFalse(system.update(LocationRequest.FOREGROUND), "equal request, equal registration: leave it alone")
        assertEquals(1, gpsRequests().size, "a no-op update must not stack registrations")

        assertTrue(system.update(LocationRequest.dormant(30_000)))
        assertEquals(
            listOf(30_000L),
            gpsRequests().map { it.intervalMillis },
            "the old registration is replaced rather than stacked, which is what a dormancy switch needs",
        )
        assertEquals(listeners, shadow.requestLocationUpdateListeners.size, "the caller's listener never changed")
    }

    @Test
    fun `update before start has nothing to reconfigure`() {
        val system = provider(LocationPermission.GRANTED_FINE)
        assertFalse(system.update(LocationRequest.dormant(30_000)))
        assertEquals(0, shadow.requestLocationUpdateListeners.size)
    }

    @Test
    fun `stop and close remove the registration`() {
        val system = provider(LocationPermission.GRANTED_FINE)
        assertTrue(system.start(LocationRequest.FOREGROUND, recorder))
        assertTrue(shadow.requestLocationUpdateListeners.isNotEmpty())

        system.stop()
        assertEquals(0, shadow.requestLocationUpdateListeners.size, "removeUpdates ran")
        assertEquals(0, gpsRequests().size)

        assertTrue(system.start(LocationRequest.FOREGROUND, recorder))
        assertEquals(1, shadow.requestLocationUpdateListeners.size, "a stopped provider is reusable")
        system.close()
        assertEquals(0, shadow.requestLocationUpdateListeners.size, "close must also stop")
    }

    // ------------------------------------------------------------------ singleFix

    @Test
    fun `singleFix refuses immediately without permission`() = runBlocking {
        val result = provider(LocationPermission.DENIED).singleFix(LocationRequest.FOREGROUND)

        val failed = result as? LocationResult.Failed
        assertNotNull(failed, "expected a Failed answer, got $result")
        assertEquals(LocationFailure.NO_PERMISSION, failed.fault.reason)
        assertEquals("DENIED", failed.fault.detail)
        assertEquals(0, shadow.requestLocationUpdateListeners.size, "it must not register first and then fail")
    }

    @Test
    fun `singleFix takes the delivered fix`() = runBlocking {
        val system = provider(LocationPermission.GRANTED_FINE)

        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            system.singleFix(LocationRequest.FOREGROUND.copy(singleFixTimeoutMillis = 5_000))
        }
        // Parked inside suspendCancellableCoroutine now, with the one-shot listener registered at 0 ms.
        val request = gpsRequests().single()
        assertEquals(0L, request.intervalMillis, "a one-shot must not be throttled")

        shadow.simulateLocation(
            frameworkLocation(LocationManager.GPS_PROVIDER, 22.5431, 114.0579, 30f, 0f, 0f, 1_700_000_002_000L),
        )
        shadowOf(Looper.getMainLooper()).idle()

        val located = pending.await() as? LocationResult.Located
        assertNotNull(located, "expected a Located answer, got $located")
        assertEquals(22.5431, located.fix.coord.lat, COORD_TOLERANCE)
        assertEquals(114.0579, located.fix.coord.lng, COORD_TOLERANCE)
        assertEquals(30f, located.fix.accuracyMeters)
        assertEquals(1_700_000_002L, located.fix.epochSeconds)
        assertEquals("gps", located.fix.providerId, "the framework provider name, not this layer's id")
        assertEquals(located.fix, system.lastFix())
    }

    @Test
    fun `a successful singleFix removes the one-shot it registered`() {
        // The success path used to leak: removal was wired only to `invokeOnCancellation`, which by definition
        // never runs when the continuation resumes with a value, so every Located one-shot left a 0 ms
        // registration behind on each enabled provider -- and stop()/close() could not clear it, because they
        // only remove the follow listener. Now removed on every exit path.
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val system = provider(LocationPermission.GRANTED_FINE)

        runBlocking {
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                system.singleFix(LocationRequest.FOREGROUND.copy(singleFixTimeoutMillis = 5_000))
            }
            assertEquals(0L, gpsRequests().single().intervalMillis, "the one-shot is registered at 0 ms")
            assertEquals(0L, networkRequests().single().intervalMillis, "on every enabled provider")
            assertEquals(
                1,
                shadow.requestLocationUpdateListeners.size,
                "requestLocationUpdateListeners counts distinct listeners: one one-shot listener, registered " +
                    "on both providers, which the two interval assertions above are the evidence for",
            )

            shadow.simulateLocation(
                frameworkLocation(LocationManager.GPS_PROVIDER, 39.9, 116.4, 10f, 0f, 0f, 1_700_000_003_000L),
            )
            shadowOf(Looper.getMainLooper()).idle()
            val located = assertNotNull(
                pending.await() as? LocationResult.Located,
                "expected a Located answer",
            )
            assertEquals("gps", located.fix.providerId, "the framework names the answering provider")
            assertEquals(located.fix, system.lastFix())

            assertEquals(emptyList<Long>(), gpsRequests().map { it.intervalMillis }, "no leaked registration")
            assertEquals(emptyList<Long>(), networkRequests().map { it.intervalMillis }, "same on network")
            assertEquals(0, shadow.requestLocationUpdateListeners.size)
            system.close()
            assertEquals(0, shadow.requestLocationUpdateListeners.size, "close stays a no-op when there is nothing to clean")
        }
    }

    @Test
    fun `a timed out singleFix does unregister`() {
        // The cancellation path is the one the production code does handle, so pin the asymmetry.
        runBlocking {
            val system = provider(LocationPermission.GRANTED_FINE)
            val result = system.singleFix(LocationRequest.FOREGROUND.copy(singleFixTimeoutMillis = 60))
            assertTrue(result is LocationResult.Failed)

            assertEquals(0, gpsRequests().size, "invokeOnCancellation removed the one-shot")
            assertEquals(0, shadow.requestLocationUpdateListeners.size)
        }
    }

    @Test
    fun `singleFix gives up on its own timeout and names it`() = runBlocking {
        val system = provider(LocationPermission.GRANTED_FINE)

        val result = system.singleFix(LocationRequest.FOREGROUND.copy(singleFixTimeoutMillis = 60))

        val failed = result as? LocationResult.Failed
        assertNotNull(failed, "expected a TIMEOUT answer, got $failed")
        assertEquals(LocationFailure.TIMEOUT, failed.fault.reason)
        assertTrue(
            assertNotNull(failed.fault.detail).contains("60ms"),
            "the detail names the caller's own timeout: <<${failed.fault.detail}>>",
        )
        assertNull(system.lastFix())
    }

    // ------------------------------------------------------------------ helpers

    private fun frameworkLocation(
        providerName: String,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        speed: Float,
        bearing: Float,
        timeMillis: Long,
    ): Location = Location(providerName).apply {
        this.latitude = latitude
        this.longitude = longitude
        this.accuracy = accuracy
        this.speed = speed
        this.bearing = bearing
        time = timeMillis
    }

    private class FixedPermissions(private val permission: LocationPermission) : LocationPermissions {
        override fun state(): LocationPermission = permission
    }

    private class Recorder : LocationListener {
        val fixes = mutableListOf<LocationFix>()
        val faults = mutableListOf<LocationFault>()

        override fun onFix(fix: LocationFix) {
            fixes += fix
        }

        override fun onFailure(fault: LocationFault) {
            faults += fault
        }
    }

    companion object {
        private const val COORD_TOLERANCE = 1e-9
    }
}
