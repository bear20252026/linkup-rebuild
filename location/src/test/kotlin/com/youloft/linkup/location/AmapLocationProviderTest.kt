package com.youloft.linkup.location

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationClientOption.AMapLocationMode
import com.youloft.linkup.location.provider.amap.AmapLocationProvider
import com.youloft.linkup.location.provider.amap.AmapSdk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the real reflective AMap adapter against the SDK-shaped doubles under `src/test/java`.
 *
 * Unlike [BaiduLocationProviderTest] there is no decompiled contract to cite: AMap is not the vendor the
 * shipping app carried, it is the second vendor that proves the interface seam. The doubles carry the
 * official AMap location SDK signatures, and the pinned behaviour is what [AmapSdk] documents as binding —
 * the official API's call order and error contract, plus this project's own option block.
 *
 * Every test here runs the production [AmapSdk]; [BindingSdk] overrides **just** `createClient` to inject
 * the inspectable double, and [MissingSdk] overrides only the two presence properties. Nothing repairs a
 * binding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmapLocationProviderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var client: AMapLocationClient
    private lateinit var provider: AmapLocationProvider
    private val recorder = Recorder()

    @Before
    fun setUp() {
        AMapLocationClient.resetState()
        client = AMapLocationClient(context)
        provider = AmapLocationProvider(context, AMAP_AK, privacyAgreed = { true }, sdk = BindingSdk(client))
    }

    // ------------------------------------------------------------------ option block

    @Test
    fun `the option block carries the request's four knobs`() {
        assertTrue(provider.start(LocationRequest.FOREGROUND, recorder))

        val option = assertNotNull(client.getLocationOption())
        assertEquals(AMapLocationMode.Hight_Accuracy, option.mode)
        assertEquals(2000, option.interval, "the foreground loop runs at the request's 2 s interval")
        assertTrue(option.needAddress, "inlinePlace=true is delivered through setNeedAddress")
        assertFalse(option.onceLocation, "continuous following is not one-shot")
        // The privacy pair precedes everything, and the key is a static.
        assertTrue(AMapLocationClient.privacyShowCount >= 1)
        assertTrue(AMapLocationClient.privacyAgreeCount >= 1)
        assertEquals(true, AMapLocationClient.lastPrivacyAgreeValue)
        assertEquals(AMAP_AK, AMapLocationClient.apiKey)
        assertEquals(
            listOf(
                "updatePrivacyShow(true,true)",
                "updatePrivacyAgree(true)",
                "setApiKey($AMAP_AK)",
                "setLocationListener",
                "setLocationOption",
                "startLocation",
            ),
            AMapLocationClient.log().drop(1),
            "start's call order: privacy -> key -> listener -> option -> start; the leading ctor(context) " +
                "entry is the double built in setUp, before the provider touched anything",
        )
    }

    @Test
    fun `the dormancy request re-points mode and interval`() {
        assertTrue(provider.start(LocationRequest.FOREGROUND, recorder))
        val dormant = LocationTuning().dormantRequest()
        assertTrue(provider.update(dormant), "the dormancy swap re-points the live client")

        val option = assertNotNull(client.getLocationOption())
        assertEquals(AMapLocationMode.Battery_Saving, option.mode)
        assertEquals(30_000, option.interval)
    }

    @Test
    fun `update ignores an identical request`() {
        assertTrue(provider.start(LocationRequest.FOREGROUND, recorder))
        assertFalse(provider.update(LocationRequest.FOREGROUND))
        assertEquals(1, client.setLocationOptionCount)
    }

    @Test
    fun `a completed void SDK call is reported as success and reaches the client`() {
        assertTrue(provider.start(LocationRequest.FOREGROUND, recorder))
        assertTrue(client.started, "startLocation really ran")
        assertEquals(1, client.startCount)

        provider.stop()
        assertFalse(client.started)
        assertEquals(1, client.stopCount)
    }

    // ------------------------------------------------------------------ install gates

    @Test
    fun `no key means not installed, without touching the SDK`() {
        val keyless = AmapLocationProvider(context, key = "", privacyAgreed = { true }, sdk = BindingSdk(client))
        assertFalse(keyless.isInstalled, "an unauthorized AMap cannot produce fixes worth trusting")
        assertEquals(0, AMapLocationClient.privacyShowCount, "the gate refuses before the SDK is touched")
    }

    @Test
    fun `privacy refusal counts as not installed`() {
        val unagreed = AmapLocationProvider(context, AMAP_AK, privacyAgreed = { false }, sdk = BindingSdk(client))
        assertFalse(unagreed.isInstalled)
        assertFalse(unagreed.start(LocationRequest.FOREGROUND, recorder))
        assertEquals(0, AMapLocationClient.privacyShowCount)
        assertEquals(
            listOf("onFailure:SERVICE_UNAVAILABLE"),
            recorder.events,
            "the reason names the privacy gate, not a vendor crash",
        )
    }

    @Test
    fun `a missing AAR reports itself not installed with the reason`() {
        val withoutAar = AmapLocationProvider(context, AMAP_AK, privacyAgreed = { true }, sdk = MissingSdk())
        assertFalse(withoutAar.isInstalled)
        assertFalse(withoutAar.start(LocationRequest.FOREGROUND, recorder))
        assertEquals(
            listOf("onFailure:SERVICE_UNAVAILABLE"),
            recorder.events,
            "the missing-reason text names the AAR, for the status screen",
        )
    }

    // ------------------------------------------------------------------ fix reading

    @Test
    fun `a gcj02 fix is normalized to the stored datum`() = runBlocking {
        assertTrue(provider.start(LocationRequest.FOREGROUND, recorder))
        client.deliver(AMapLocation())

        val fix = assertNotNull(recorder.fixes.singleOrNull())
        // geo_golden_156.txt: gcj02_to_wgs84(39.90469,116.40717) = 39.903286587,116.400927605
        assertEquals(39.903286587, fix.coord.lat, 1e-8)
        assertEquals(116.400927605, fix.coord.lng, 1e-8)
        assertEquals(CoordSystem.WGS84, fix.coord.system, "stored fixes are always WGS84")
        assertEquals("amap", fix.providerId)
        assertEquals(1, fix.vendorFixType, "getLocationType passes through for the status screen")
    }

    @Test
    fun `speed passes through in metres per second, never divided by 3_6`() = runBlocking {
        assertTrue(provider.start(LocationRequest.FOREGROUND, recorder))
        val raw = AMapLocation()
        raw.speed = 1.5f
        client.deliver(raw)

        val fix = assertNotNull(recorder.fixes.singleOrNull())
        assertEquals(
            1.5f,
            fix.speedMetersPerSecond,
            "AMapLocation.getSpeed() is already m/s (android.location.Location); the Baidu km/h division " +
                "has no counterpart here and would make the cadence 3.6x stingier",
        )
    }

    @Test
    fun `place fields come off the fix, street number from getStreetNum`() = runBlocking {
        assertTrue(provider.start(LocationRequest.FOREGROUND, recorder))
        client.deliver(AMapLocation())

        val place = assertNotNull(recorder.fixes.single().place)
        assertEquals("北京市", place.province)
        assertEquals("北京", place.city)
        assertEquals("东城区", place.district)
        assertEquals("长安街", place.street)
        assertEquals("1号", place.streetNumber, "AMap spells it getStreetNum, not getStreetNumber")
        assertEquals("天安门", place.poiName, "AMap has a single poiName string, not a POI list")
    }

    @Test
    fun `an errorCode fix is a failure, before any coordinate is read`() = runBlocking {
        assertTrue(provider.start(LocationRequest.FOREGROUND, recorder))
        val failing = AMapLocation()
        failing.errorCode = 7
        failing.errorInfo = "KEY鉴权失败"
        client.deliver(failing)

        assertTrue(recorder.fixes.isEmpty(), "a failed fix never produces a LocationFix")
        assertEquals(listOf("onFailure:VENDOR_ERROR"), recorder.events)
    }

    @Test
    fun `a placeholder coordinate is refused like every other vendor`() = runBlocking {
        assertTrue(provider.start(LocationRequest.FOREGROUND, recorder))
        val raw = AMapLocation()
        raw.latitude = 0.0
        raw.longitude = 0.0
        client.deliver(raw)

        assertTrue(recorder.fixes.isEmpty())
        assertEquals(listOf("onFailure:INVALID_FIX"), recorder.events)
    }

    @Test
    fun `lastFix survives a read and empty stays empty`() = runBlocking {
        assertTrue(provider.lastFix() == null)
        assertTrue(provider.start(LocationRequest.FOREGROUND, recorder))
        client.deliver(AMapLocation())
        assertEquals(39.903286587, assertNotNull(provider.lastFix()).coord.lat, 1e-8)
    }

    // ------------------------------------------------------------------ single fix

    @Test
    fun `single fix goes through the once-location path and answers from the callback`() = runBlocking {
        // UNDISPATCHED so the coroutine reaches its suspension point (and has registered both client and
        // listener) before the deliver below fires; a queued start would leave deliver with no listener.
        val pending = async(start = CoroutineStart.UNDISPATCHED) { provider.singleFix() }
        client.deliver(AMapLocation())
        val fix = assertNotNull(pending.await().fixOrNull)
        assertEquals(39.903286587, fix.coord.lat, 1e-8)

        val option = assertNotNull(client.getLocationOption())
        assertTrue(option.onceLocation, "AMap's one-shot is the onceLocation option, not a request-only call")
    }

    // ------------------------------------------------------------------ close

    @Test
    fun `close stops and releases, one client per process`() = runBlocking {
        assertTrue(provider.start(LocationRequest.FOREGROUND, recorder))
        provider.close()
        assertTrue(client.stopCount >= 1)
        assertEquals(1, client.destroyCount, "AMap's own release call is part of close")
    }

    @Test
    fun `an unmodified AmapSdk creates, configures and starts the client by itself`() = runBlocking {
        val plain = AmapLocationProvider(context, AMAP_AK, privacyAgreed = { true }, sdk = AmapSdk())
        assertTrue(plain.isInstalled, "the doubles are on the test classpath, so presence is real here")
        assertTrue(plain.start(LocationRequest.FOREGROUND, recorder))
        assertTrue(AMapLocationClient.log().isNotEmpty(), "the unshimmed path reached the SDK")
        plain.close()
    }

    // ------------------------------------------------------------------ fixtures

    /** Injects the double; changes no binding semantics. See [BaiduLocationProviderTest] for the same shape. */
    private class BindingSdk(private val injected: AMapLocationClient) : AmapSdk() {
        override fun createClient(context: Context): Any? = injected
    }

    private class MissingSdk : AmapSdk() {
        override val isPresent: Boolean = false
        override val missingReason: String = "找不到 com.amap.api.location.AMapLocationClient：本 build 未包含高德定位 AAR"
    }

    private class Recorder : LocationListener {
        val fixes = mutableListOf<LocationFix>()
        val events = mutableListOf<String>()

        override fun onFix(fix: LocationFix) {
            fixes.add(fix)
        }

        override fun onFailure(fault: LocationFault) {
            events.add("onFailure:${fault.reason}")
        }
    }

    private companion object {
        /** A syntactically valid key for the double; authorization itself is the vendor's problem. */
        const val AMAP_AK = "test-amap-key"
    }
}
