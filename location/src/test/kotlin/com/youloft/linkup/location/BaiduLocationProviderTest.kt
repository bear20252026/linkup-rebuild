package com.youloft.linkup.location

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baidu.location.BDLocation
import com.baidu.location.BDLocationListener
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.baidu.location.LocationClientOption.FirstLocType
import com.baidu.location.LocationClientOption.LocationMode
import com.baidu.location.Poi
import com.youloft.linkup.location.provider.baidu.BaiduFix
import com.youloft.linkup.location.provider.baidu.BaiduLocationProvider
import com.youloft.linkup.location.provider.baidu.BaiduSdk
import java.io.File
import java.lang.reflect.InvocationHandler
import kotlin.math.abs
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
import org.robolectric.annotation.Config

/**
 * Runs the real reflective Baidu adapter against the SDK-shaped doubles under `src/test/java`, which is the
 * only way the vendor mapping can be checked offline: the AAR did not survive the loss of the source tree,
 * so `recovered/baidu_location_api_156.txt` (every bound member, with its APK file:line) is the contract and
 * the doubles carry those signatures.
 *
 * The behaviour being pinned is the shipping app's, from `_raw/out_jadx_156/sources/com/youloft/watcher/`:
 * the option block of `manager/x0.java:678-691` (identical to 1.6.6's `dg/a.java:98-117`), the fix reading
 * of `manager/x0.java:284-304`, and the one-shot path of `manager/s0.java:3031`.
 *
 * ## Every test here runs the production `BaiduSdk`
 *
 * Nothing in this file repairs or replaces a binding. [BaiduSdk] was rewritten after two defects it used to
 * carry were fixed, and the tests moved with it:
 *
 *  * the nested enum lookup is `owner.name + "$" + nested` (`BaiduSdk.kt:243-255`), so
 *    `setLocationMode(Hight_Accuracy|Battery_Saving)` and `firstLocType = ACCURACY_IN_FIRST_LOC` really
 *    arrive. `the option block is the one the shipping app ran` and
 *    `update ignores an identical request and re-points the client for the dormancy one` assert the values
 *    themselves, `the nested enum constants reach the option under their real binary name` asserts them
 *    straight off `buildOption`, and `option.calls` is what proves the setter ran with a non-null mode
 *    rather than the field happening to still hold the SDK's own `Hight_Accuracy` default;
 *  * a void method counts as success when it was found and did not throw (`BaiduSdk.kt:188-205`), so
 *    `setLocOption` / `registerLocationListener` / `start` / `stop` report true and `start()` really reaches
 *    `LocationClient.start()` -- `a completed void SDK call is reported as success and reaches the client`
 *    pins that directly, because the opposite reading is what silently stopped the provider from ever
 *    starting.
 *
 * `buildOption` also fails loudly instead of half-configuring: a missing setter, field or enum member makes
 * it return null and put the reason in `missingReason` (`BaiduSdk.kt:103-141`), which is why `start()` can
 * then return false instead of shipping a half-set option. The reading side of the same rule -- a payload
 * without the getters the reader needs is refused by name -- is what
 * `a payload missing a getter the reader needs is refused by name, not read as zero` covers; the writing
 * side cannot be reached from a test, because every member `buildOption` needs is on the classpath.
 *
 * The only subclass of [BaiduSdk] left is [BindingSdk], which overrides **just** `createClient`:
 * `LocationClient` has no registry and the provider keeps its client private, so a test that has to read the
 * counters or deliver a fix must hand the instance in. It changes no binding semantics, and
 * `an unmodified BaiduSdk creates, configures and starts the client by itself` is the same path with the
 * injection removed. [MissingSdk] overrides only the two presence properties, which is the honest way to
 * stand in for a build without the AAR; there is deliberately no unregister binding to simulate, because
 * neither APK has one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BaiduLocationProviderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * The double the adapter is handed, kept so the test can read its counters and deliver fixes.
     * [BindingSdk] injects it; see its comment for why the injection is the only shim left.
     */
    private lateinit var client: LocationClient
    private lateinit var provider: BaiduLocationProvider
    private val recorder = Recorder()

    @Before
    fun setUp() {
        LocationClient.resetState()
        client = LocationClient(context)
        provider = BaiduLocationProvider(context, BAIDU_AK, privacyAgreed = { true }, sdk = BindingSdk(client))
    }

    // ------------------------------------------------------------------ option block

    @Test
    fun `the option block is the one the shipping app ran`() {
        assertTrue(provider.start(LocationRequest.FOREGROUND, recorder))

        val option = assertNotNull(client.getLocOption())
        // x0.java:678-691 / dg/a.java:98-117, transcribed field by field.
        assertEquals(2000, option.scanSpan, "the foreground loop ran at 2 s (locationScanMills)")
        assertEquals(
            "bd09ll",
            option.coorType,
            "dg/a.java:76-79: Baidu's own datum, otherwise the fix and the app's cached stay point sit " +
                "~500 m apart in a datum that never existed",
        )
        assertEquals(
            "all",
            option.addrType,
            "setIsNeedAddress(true) rewrites addrType itself, exactly as LocationClientOption.java:334-336",
        )
        assertTrue(option.disableLocCache, "a cached fix would pass the cadence gate as fresh movement")
        assertTrue(option.isIgnoreKillProcess)
        assertEquals(120_000, option.wifiCacheTimeOut, "dg/a.java:100-117")
        assertTrue(option.isEnableBeidouMode)
        assertEquals(
            FirstLocType.ACCURACY_IN_FIRST_LOC,
            option.firstLocType,
            "dg/a.java:113: the binding writes it through the field, and the enum lookup now resolves " +
                "(BaiduSdk.kt:133 with the one-dollar binary name at :245)",
        )
        assertEquals(
            LocationMode.Hight_Accuracy,
            option.locationMode,
            "HIGH accuracy is what FOREGROUND asks for; the calls list below is the proof it was written " +
                "rather than inherited from the SDK's own default",
        )
        assertTrue(option.isNeedAddress)
        assertTrue(option.isNeedLocationPoiList, "Baidu has no poiName field; the name came from the POI list")
        assertTrue(option.openGps, "setOpenGnss(true) writes the SDK's openGps field")
        assertFalse(option.enableSimulateGps)
        assertFalse(option.isOnceLocation, "continuous sampling, not the one-shot option")
        assertEquals(
            listOf(
                "setIsNeedAddress(true)",
                "setIsNeedLocationPoiList(true)",
                "setOpenGnss(true)",
                "setEnableSimulateGnss(false)",
                "setOnceLocation(false)",
                "setLocationMode(Hight_Accuracy)",
            ),
            option.calls,
            "the setter block in the order the adapter writes it, ending with the real (non-null) mode",
        )
        assertFalse(
            option.calls.any { it.startsWith("setLocationMode(null") },
            "a null mode would be a runtime default the recovered app never relied on: " + option.calls,
        )

        // Client-level bindings, all still reached reflectively.
        assertEquals(
            true,
            LocationClient.agreedPrivacy,
            "LocationClient.java:1122, called from SdkInitManager.java:281 in the original",
        )
        assertEquals(BAIDU_AK, LocationClient.apiKey, "x0.java:676")
        assertTrue(
            client.hasInterfaceListener(),
            "registerLocationListener must bind the BDLocationListener interface overload " +
                "(LocationClient.java:1213); the abstract-class overload at :1203 cannot be proxied",
        )
        assertEquals(1, client.registerCount)
        assertEquals(1, client.setLocOptionCount)
        assertEquals(1, client.startCount, "start() exactly once per start() call")
        assertTrue(LocationClient.log().contains("setAgreePrivacy(true)"), LocationClient.log().toString())
        assertTrue(LocationClient.log().contains("setKey($BAIDU_AK)"), LocationClient.log().toString())
        assertTrue(
            LocationClient.log().contains("registerLocationListener(interface)"),
            LocationClient.log().toString(),
        )
        assertFalse(
            LocationClient.log().contains("registerLocationListener(abstract)"),
            "BDAbstractLocationListener is a class, so a proxy can never implement it: " + LocationClient.log(),
        )
    }

    // ------------------------------------------------------------------ fix mapping, x0.java:284-304

    @Test
    fun `a GNSS fix is normalised exactly like the original callback did`() {
        provider.start(LocationRequest.FOREGROUND, recorder)
        val before = System.currentTimeMillis() / 1000

        client.deliver(gnssFix())

        val neutral = recorder.fixes.single()
        val fix = neutral.coord
        // Golden row: bd09_to_wgs84(39.90469,116.40717) = 39.896937039,116.394545721, i.e. the h0.c call
        // at x0.java:290. GeoMathTest pins the conversion; this pins that the adapter used it.
        assertEquals(39.896937039, fix.lat, COORD_TOLERANCE, "latitude must match the golden bd09->wgs84 row")
        assertEquals(116.394545721, fix.lng, COORD_TOLERANCE, "longitude must match the golden bd09->wgs84 row")
        assertEquals(
            bd09ToWgs84(Coord(39.90469, 116.40717, CoordSystem.BD09)),
            fix,
            "the adapter's own job: hand the vendor coordinate to the module's converter, unchanged",
        )
        assertEquals(CoordSystem.WGS84, fix.system, "the layer's currency; the datum tag must not survive")

        assertEquals(1.0f, neutral.speedMetersPerSecond, "x0.java:288 divides Baidu's km/h by 3.6 on a GNSS fix")
        assertEquals(25f, neutral.accuracyMeters, "accuracy is BDLocation.getRadius()")
        assertEquals(61, neutral.vendorFixType, "the locType is reported, not swallowed")
        assertEquals("baidu", neutral.providerId)
        assertTrue(
            kotlin.math.abs(neutral.epochSeconds - before) <= 2,
            "x0.java:304 stamps wall clock at callback time and ignores the SDK's own fix time; " +
                "got ${neutral.epochSeconds} against ${before}s",
        )

        val place = assertNotNull(neutral.place)
        assertEquals("王府井", place.poiName, "x0.java:299-304 takes the FIRST Poi's name")
        assertEquals("北京市", place.province)
        assertEquals("北京", place.city)
        assertEquals("东城区", place.district)
        assertEquals("长安街", place.street)
        assertEquals("1号", place.streetNumber)
        assertFalse(place.isBlank)
        assertNull(neutral.bearingDegrees, "the original never read a bearing off BDLocation")
        assertEquals(neutral, provider.lastFix())
        assertTrue(recorder.faults.isEmpty(), "a valid fix must not also report a failure")
    }

    @Test
    fun `a network fix has its speed zeroed the way the original did`() {
        provider.start(LocationRequest.FOREGROUND, recorder)
        val location = gnssFix().apply {
            locType = 161 // TypeNetWorkLocation, BDLocation.java:96
            speed = 30f
        }

        client.deliver(location)

        val fix = recorder.fixes.single()
        assertEquals(
            0f,
            fix.speedMetersPerSecond,
            "x0.java:288-289 trusts getSpeed() only for locType 61 and writes 0.0f for every other fix " +
                "type; the reporting cadence reads that as 'stopped'",
        )
        assertEquals(161, fix.vendorFixType)
    }

    @Test
    fun `NaN and infinite speed on a GNSS fix both land on zero`() {
        provider.start(LocationRequest.FOREGROUND, recorder)

        client.deliver(gnssFix().apply { speed = Float.NaN })
        client.deliver(gnssFix().apply { speed = Float.POSITIVE_INFINITY })
        client.deliver(gnssFix().apply { speed = Float.NEGATIVE_INFINITY })

        assertEquals(
            listOf(0f, 0f, 0f),
            recorder.fixes.map { it.speedMetersPerSecond },
            "x0.java:287 rewrites a non-finite speed to 0.0f",
        )
    }

    @Test
    fun `placeholder coordinates fail with INVALID_FIX and deliver nothing`() {
        provider.start(LocationRequest.FOREGROUND, recorder)

        client.deliver(gnssFix().apply { latitude = 0.0 })
        client.deliver(gnssFix().apply { longitude = 0.0 })
        client.deliver(gnssFix().apply { latitude = Double.MIN_VALUE })
        client.deliver(gnssFix().apply { longitude = Double.MIN_VALUE })

        assertTrue(recorder.fixes.isEmpty(), "x0.java:284-287 drops the sample before it reaches the bean")
        assertEquals(4, recorder.faults.size, LocationClient.log().toString())
        recorder.faults.forEach { fault ->
            assertEquals(LocationFailure.INVALID_FIX, fault.reason, fault.toString())
            assertEquals(61, fault.vendorCode, "the locType is kept beside the failure for triage")
        }
        assertNull(provider.lastFix(), "an invalid sample must not become the last known position")
    }

    @Test
    fun `a null payload is an invalid fix rather than a crash`() {
        // deliver() cannot fabricate a null BDLocation, so the x0.java:281-283 branch is pinned at the
        // reader the proxy feeds.
        assertEquals(
            BaiduFix.Invalid(LocationFault(LocationFailure.INVALID_FIX, detail = "BDLocation 为 null")),
            BaiduSdk().readFix(null, "baidu", 1_700_000_000L),
        )
    }

    @Test
    fun `a payload missing a getter the reader needs is refused by name, not read as zero`() {
        // The rewritten BaiduSdk refuses to invent defaults for absent members: buildOption stops and says so
        // (BaiduSdk.kt:103-141), and readFix does the same on the reading side (BaiduSdk.kt:155-157). A plain
        // Object() is an SDK answer with no getLatitude/getLongitude at all, and `get` cannot tell "missing"
        // from "null", so the fix has to be dropped rather than reported as 0,0 -- which the placeholder rule
        // would otherwise have rejected anyway.
        val outcome = BaiduSdk().readFix(Any(), "baidu", 1_700_000_000L)

        val invalid = outcome as? BaiduFix.Invalid
        assertNotNull(invalid, "expected an Invalid reading of a payload with no getters, got $outcome")
        assertEquals(LocationFailure.VENDOR_ERROR, invalid.fault.reason, invalid.fault.toString())
        assertTrue(
            assertNotNull(invalid.fault.detail).contains("getLatitude"),
            "the detail has to name the member that is missing: <<${invalid.fault.detail}>>",
        )
        assertEquals(0, invalid.fault.vendorCode, "an unreadable locType is 0, and that is the honest answer")
    }

    @Test
    fun `coorType gcj02 is converted by the gcj02 path not the bd09 one`() {
        provider.start(LocationRequest.FOREGROUND, recorder)
        val location = gnssFix().apply { coorType = "gcj02" }

        client.deliver(location)

        val fix = recorder.fixes.single()
        // Golden row: gcj02_to_wgs84(39.90469,116.40717) = 39.903286587,116.400927605.
        assertEquals(39.903286587, fix.coord.lat, COORD_TOLERANCE, "the gcj02 family's latitude")
        assertEquals(116.400927605, fix.coord.lng, COORD_TOLERANCE, "the gcj02 family's longitude")
        assertEquals(gcj02ToWgs84(Coord(39.90469, 116.40717, CoordSystem.GCJ02)), fix.coord)
        assertFalse(
            fix.coord == bd09ToWgs84(Coord(39.90469, 116.40717, CoordSystem.BD09)),
            "both datum paths gave the same answer, so this would prove nothing",
        )
        assertNotEqualsRow(fix.coord, rowFromGolden("bd09_to_wgs84", "39.90469,116.40717"))
    }

    @Test
    fun `coorType wgs84 and an unknown spelling are left alone`() {
        provider.start(LocationRequest.FOREGROUND, recorder)

        client.deliver(gnssFix().apply { coorType = "wgs84" })
        client.deliver(gnssFix().apply { coorType = "something-else" })

        assertEquals(2, recorder.fixes.size)
        recorder.fixes.forEach { fix ->
            assertEquals(39.90469, fix.coord.lat, COORD_TOLERANCE, "an untagged fix is already the layer's datum")
            assertEquals(116.40717, fix.coord.lng, COORD_TOLERANCE)
        }
    }

    @Test
    fun `an empty or missing poi list leaves poiName as the empty string the original wrote`() {
        provider.start(LocationRequest.FOREGROUND, recorder)

        client.deliver(gnssFix().apply { poiList = null })
        client.deliver(gnssFix().apply { poiList = emptyList() })
        client.deliver(gnssFix().apply { poiList = listOf(Poi("")) })
        client.deliver(gnssFix().apply { poiList = listOf(Poi("南锣鼓巷"), Poi("什刹海")) })

        assertEquals(4, recorder.fixes.size)
        val names = recorder.fixes.map { assertNotNull(it.place).poiName }
        // x0.java:299-304 substitutes "" when the list is null or empty -- Baidu has no poiName field of its
        // own, so an absent POI is an empty name, never a null one. BaiduSdk.firstPoiName (BaiduSdk.kt:268)
        // reproduces that, which also keeps Place.isBlank's all-empty rule honest.
        assertEquals(listOf("", "", "", "南锣鼓巷"), names, "poiName per delivered fix")
        assertEquals(0, recorder.fixes.count { assertNotNull(it.place).poiName == null })
        assertTrue(recorder.faults.isEmpty(), "an empty POI list is not a failure")
    }

    // ------------------------------------------------------------------ update

    @Test
    fun `update ignores an identical request and re-points the client for the dormancy one`() {
        provider.start(LocationRequest.FOREGROUND, recorder)
        assertEquals(1, client.setLocOptionCount)

        assertFalse(
            provider.update(LocationRequest.FOREGROUND),
            "dg/a.java:56-121 returned null for an unchanged snapshot and s0.java:764-785 only called " +
                "setLocOption when it changed",
        )
        assertEquals(1, client.setLocOptionCount, "the SDK must not be touched for a no-op update")
        assertFalse(provider.update(LocationRequest(intervalMillis = 2_000)), "equal by value, not by identity")

        assertTrue(provider.update(LocationRequest.dormant(30_000)))
        assertEquals(2, client.setLocOptionCount)
        assertEquals(1, client.startCount, "an update re-points the option, it does not restart the client")

        val option = assertNotNull(client.getLocOption())
        assertEquals(
            LocationMode.Battery_Saving,
            option.locationMode,
            "s0.java:810 is what asks for it, and the mode now reaches the option through the real " +
                "enum lookup instead of staying at the SDK's Hight_Accuracy default",
        )
        assertTrue(
            option.calls.contains("setLocationMode(Battery_Saving)"),
            "the setter really ran with the dormancy mode: ${option.calls}",
        )
        assertEquals(30_000, option.scanSpan, "locationScanMillsWhenDormancy")
        assertEquals("bd09ll", option.coorType, "the constants stay constant across an update")
        assertEquals(FirstLocType.ACCURACY_IN_FIRST_LOC, option.firstLocType, "also written on the update path")
        assertTrue(option.disableLocCache)
        assertFalse(option.isOnceLocation)
    }

    @Test
    fun `update before start is refused`() {
        assertFalse(provider.update(LocationRequest.dormant(30_000)), "there is no client to re-point yet")
        assertEquals(0, client.setLocOptionCount)
        assertEquals(0, client.startCount)
    }

    // ------------------------------------------------------------------ privacy gate

    @Test
    fun `a device that has not agreed the privacy policy reports itself unusable`() {
        val gated = BaiduLocationProvider(context, BAIDU_AK, privacyAgreed = { false }, sdk = BindingSdk(client))

        assertFalse(
            gated.isInstalled,
            "LocationClient.java:910-911 throws in the constructor when the flag is unset, so this provider " +
                "can never produce a fix",
        )
        assertFalse(gated.start(LocationRequest.FOREGROUND, recorder))

        val fault = recorder.faults.single()
        assertEquals(LocationFailure.SERVICE_UNAVAILABLE, fault.reason)
        assertTrue(
            assertNotNull(fault.detail).contains("隐私政策"),
            "the detail has to say why, because isInstalled()==false is otherwise indistinguishable from " +
                "a missing AAR: <<${fault.detail}>>",
        )
        assertTrue(recorder.fixes.isEmpty())
        assertEquals(0, client.startCount, "no client may be constructed or started behind the gate")
        assertFalse(LocationClient.log().contains("setAgreePrivacy(true)"), LocationClient.log().toString())
    }

    // ------------------------------------------------------------------ singleFix

    @Test
    fun `singleFix takes the delivered answer and asks for a one-shot option`() = runBlocking {
        client.requestLocationResult = 0

        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            provider.singleFix(LocationRequest.FOREGROUND.copy(singleFixTimeoutMillis = 5_000))
        }
        // UNDISPATCHED leaves us parked inside awaitOneShot, exactly where the SDK would answer.
        assertEquals(1, client.requestLocationCount, "s0.java:775 requestLocation()")
        assertTrue(
            assertNotNull(client.getLocOption()).isOnceLocation,
            "s0.java:3031 built the option with the once flag before requestLocation()",
        )

        client.deliver(gnssFix())

        val located = pending.await() as? LocationResult.Located
        assertNotNull(located, "expected a Located answer, got ${pending.await()}")
        assertEquals(39.896937039, located.fix.coord.lat, COORD_TOLERANCE)
        assertEquals(116.394545721, located.fix.coord.lng, COORD_TOLERANCE)
        assertEquals("baidu", located.fix.providerId)
        assertEquals(located.fix, provider.lastFix())
        assertFalse(client.started, "a one-shot never starts continuous sampling")
    }

    @Test
    fun `singleFix answers a refused request immediately with the vendor code`() = runBlocking {
        client.requestLocationResult = 5

        val result = async(start = CoroutineStart.UNDISPATCHED) {
            provider.singleFix(LocationRequest.FOREGROUND.copy(singleFixTimeoutMillis = 5_000))
        }.await()

        val failed = result as? LocationResult.Failed
        assertNotNull(failed, "expected a Failed answer, got $result")
        assertEquals(
            5,
            failed.fault.vendorCode,
            "requestLocation()'s int is the only evidence an on-call has; the original only logged it " +
                "(s0.java:775) but an enum cannot carry it",
        )
        assertEquals(LocationFailure.VENDOR_ERROR, failed.fault.reason)
        assertTrue(assertNotNull(failed.fault.detail).contains("5"), failed.fault.detail ?: "")
        assertEquals(0, client.startCount, "a one-shot does not start continuous sampling")
        assertNull(provider.lastFix())
    }

    @Test
    fun `singleFix gives up on its own timeout when the SDK never answers`() = runBlocking {
        client.requestLocationResult = 0
        val startedAt = System.currentTimeMillis()

        val result = provider.singleFix(LocationRequest.FOREGROUND.copy(singleFixTimeoutMillis = 60))

        val failed = result as? LocationResult.Failed
        assertNotNull(failed, "expected a TIMEOUT answer, got $result")
        assertEquals(LocationFailure.TIMEOUT, failed.fault.reason)
        assertTrue(
            assertNotNull(failed.fault.detail).contains("60ms"),
            "the detail carries the timeout that fired: <<${failed.fault.detail}>>",
        )
        assertTrue(System.currentTimeMillis() - startedAt < 5_000, "the tiny timeout was not honoured")
        assertEquals(1, client.requestLocationCount)
        assertNull(provider.lastFix())
    }

    @Test
    fun `singleFix reports the same failure as start when the SDK is missing`() = runBlocking {
        val absent = BaiduLocationProvider(context, BAIDU_AK, { true }, MissingSdk())

        val result = absent.singleFix(LocationRequest.FOREGROUND.copy(singleFixTimeoutMillis = 60))

        val failed = result as? LocationResult.Failed
        assertNotNull(failed, "expected a Failed answer, got $result")
        assertEquals(LocationFailure.SERVICE_UNAVAILABLE, failed.fault.reason)
        assertTrue(
            assertNotNull(failed.fault.detail).contains("百度定位不可用"),
            "the reason comes from the binding's own missingReason: <<${failed.fault.detail}>>",
        )
        assertEquals(0, client.startCount)
        assertTrue(recorder.fixes.isEmpty())
    }

    // ------------------------------------------------------------------ stop / close

    @Test
    fun `stop and close hand the SDK back and a later start re-creates the client`() {
        provider.start(LocationRequest.FOREGROUND, recorder)

        provider.stop()
        assertEquals(1, client.stopCount, "LocationClient.java:1344")
        assertFalse(client.started)

        provider.close()
        assertEquals(2, client.stopCount, "the contract says close must also stop")
        assertEquals(1, client.registerCount, "close registers nothing, so it cannot unregister either")
        assertTrue(
            client.hasInterfaceListener(),
            "the SDK has no unregister, so the proxy it holds is dropped along with the client rather than " +
                "removed from it -- exactly why the original kept one client per process (manager/x0.java)",
        )
        assertFalse(
            LocationClient.log().any { it.contains("unregister") },
            "and no unregister was attempted: ${LocationClient.log()}",
        )
        assertFalse(
            LocationClient::class.java.methods.any { it.name.contains("nregister") },
            "neither APK declares one (recovered/baidu_location_api_156.txt:15-16), so the double must not " +
                "offer one either or the binding would pass a test the real SDK cannot answer",
        )
        assertFalse(
            BaiduSdk::class.java.methods.any { it.name.contains("nregister") },
            "and the production binding must not invent one: close() can only stop and drop",
        )

        assertTrue(provider.start(LocationRequest.dormant(30_000), recorder))
        assertEquals(2, client.registerCount, "close dropped the client, so the next start creates and registers")
        assertEquals(2, client.setLocOptionCount)
        assertEquals(2, client.startCount, "the restarted provider really starts")
        assertTrue(client.hasInterfaceListener())

        client.deliver(gnssFix())
        assertEquals(1, recorder.fixes.size, "the freshly registered proxy is the live one")
    }

    // ------------------------------------------------------------------ fall-through

    @Test
    fun `a build without the AAR is not installed and the list falls through to the framework`() {
        val baidu = BaiduLocationProvider(context, BAIDU_AK, { true }, MissingSdk())
        val framework = StubProvider("system")

        assertFalse(baidu.isInstalled)
        assertFalse(baidu.start(LocationRequest.FOREGROUND, recorder))
        val fault = recorder.faults.single()
        assertEquals(LocationFailure.SERVICE_UNAVAILABLE, fault.reason)
        assertTrue(
            assertNotNull(fault.detail).contains("LocationClient"),
            "start() says which class it could not find, because a bare SERVICE_UNAVAILABLE is " +
                "indistinguishable from the privacy gate: <<${fault.detail}>>",
        )

        val providers = LocationProviders(listOf(baidu, framework))
        assertEquals(
            framework,
            providers.preferred(),
            "LocationProviders.isInstalled is the fall-through that keeps a keyless build locating at all",
        )
        assertEquals(listOf("system"), providers.installed().map { it.id })
        assertTrue(
            providers.describe().contains("baidu installed=false"),
            providers.describe(),
        )
    }

    @Test
    fun `capabilities say what the SDK can do`() {
        assertEquals("baidu", provider.id)
        val capabilities = provider.capabilities
        assertEquals(CoordSystem.BD09, capabilities.nativeCoordSystem, "Baidu answers bd09ll (dg/a.java:76-79)")
        assertTrue(capabilities.inlinePlace, "setIsNeedAddress(true) means no PlaceResolver is needed")
        assertTrue(capabilities.poiList, "setIsNeedLocationPoiList(true)")
        assertTrue(capabilities.needsVendorKey)
        assertTrue(capabilities.supportsRuntimeInterval, "s0.java:769-774 swapped setLocOption on a live client")
        assertTrue(provider.isInstalled, "the doubles are on the classpath, so this build does have an SDK")
    }

    // ------------------------------------------------------------------ the reflection contract itself

    @Test
    fun `the nested enum constants reach the option under their real binary name`() {
        // A nested enum's binary name carries exactly one '$' (BaiduSdk.kt:243-255). The binding used to
        // build two, Class.forName then always threw ClassNotFoundException, and the option reached the SDK
        // with locationMode == null and firstLocType == null -- invisible, because the field write that
        // follows is a no-op rather than an error. This runs the production BaiduSdk with no overrides.
        val plain = BaiduSdk()
        assertTrue(
            plain.isPresent,
            "com.baidu.location.LocationClient must be on the test classpath or this suite says nothing " +
                "about the real SDK: ${plain.missingReason}",
        )
        assertEquals(
            listOf(FirstLocType.SPEED_IN_FIRST_LOC, FirstLocType.ACCURACY_IN_FIRST_LOC),
            FirstLocType.values().toList(),
            "the constants the binding looks for are really there, so a null would be the binding's fault " +
                "rather than a limitation of the harness",
        )

        val option = assertNotNull(plain.buildOption(LocationRequest.FOREGROUND)) as LocationClientOption
        assertEquals(2000, option.scanSpan, "plain fields work")
        assertEquals("bd09ll", option.coorType)
        assertEquals(120_000, option.wifiCacheTimeOut)
        assertTrue(option.disableLocCache)
        assertEquals(
            LocationMode.Hight_Accuracy,
            option.locationMode,
            "HIGH accuracy is what FOREGROUND means (BaiduSdk.kt:263-266)",
        )
        assertEquals(FirstLocType.ACCURACY_IN_FIRST_LOC, option.firstLocType, "dg/a.java:113")
        assertTrue(option.calls.contains("setLocationMode(Hight_Accuracy)"), option.calls.toString())
        assertEquals(
            "",
            plain.missingReason,
            "a complete option block leaves the binding nothing to complain about: <<${plain.missingReason}>>",
        )

        val dormant = assertNotNull(plain.buildOption(LocationRequest.dormant(30_000))) as LocationClientOption
        assertEquals(30_000, dormant.scanSpan, "the interval still lands")
        assertEquals(LocationMode.Battery_Saving, dormant.locationMode, "s0.java:810's dormancy mode")
        assertFalse(
            dormant.locationMode == option.locationMode,
            "both requests produced the same mode, so the accuracy mapping would prove nothing",
        )
    }

    @Test
    fun `a completed void SDK call is reported as success and reaches the client`() {
        // `Method.invoke` returns null for every void method, so success has to mean "the method was found
        // and did not throw" (BaiduSdk.kt:188-205). Reading the return value instead made setLocOption,
        // registerLocationListener, start and stop all look like refusals, which is how the provider ended up
        // configuring a client it then never started. Production bindings, production BaiduSdk; the only test
        // object here is a client the test itself constructed so it can read its counters.
        val sdk = BaiduSdk()
        val fresh = LocationClient(context)
        val option = assertNotNull(sdk.buildOption(LocationRequest.FOREGROUND))
        val proxy = assertNotNull(sdk.makeListener(InvocationHandler { _, _, _ -> null }))
        assertTrue(proxy is BDLocationListener, "the proxy implements the interface overload it is bound to")

        assertTrue(sdk.setLocOption(fresh, option), "void setLocOption is a success once it ran")
        assertEquals(1, fresh.setLocOptionCount, "and the SDK was really called")
        assertTrue(sdk.registerListener(fresh, proxy))
        assertTrue(fresh.hasInterfaceListener(), "the interface overload, which is what a proxy can satisfy")
        assertEquals(1, fresh.registerCount)
        assertTrue(sdk.start(fresh))
        assertTrue(fresh.started)
        assertTrue(sdk.stop(fresh))
        assertFalse(fresh.started)
        assertEquals(1, fresh.stopCount)

        fresh.requestLocationResult = 0
        assertEquals(0, sdk.requestLocation(fresh), "the int-returning call still answers with its int")
        assertFalse(sdk.setLocOption(fresh, "not a LocationClientOption"), "an unanswerable call is still false")
        assertEquals(
            1,
            fresh.setLocOptionCount,
            "and it never reached the SDK, so the counters keep telling the truth",
        )
    }

    @Test
    fun `an unmodified BaiduSdk creates, configures and starts the client by itself`() {
        // The one test with no injection at all: `createClient` really runs
        // `LocationClient(Context).newInstance(...)` against the double, so the reflective construction path
        // the production build takes is exercised end to end. The client instance is not reachable from here
        // -- that is why the other tests inject one -- but the double's static call log records every step.
        LocationClient.resetState()
        val plain = BaiduLocationProvider(context, BAIDU_AK, { true }, BaiduSdk())

        assertTrue(plain.isInstalled)
        assertTrue(plain.start(LocationRequest.FOREGROUND, recorder), "the whole path, with no test binding in it")
        assertEquals(
            listOf(
                "setAgreePrivacy(true)",
                "setKey($BAIDU_AK)",
                "ctor(context)",
                "registerLocationListener(interface)",
                "setLocOption",
                "start",
            ),
            LocationClient.log(),
            "prepare() then buildOption/setLocOption/start, in that order",
        )
        assertEquals(BAIDU_AK, LocationClient.apiKey)
        assertEquals(true, LocationClient.agreedPrivacy)
    }

    // ------------------------------------------------------------------ helpers

    private fun gnssFix(): BDLocation = BDLocation().apply {
        latitude = 39.90469
        longitude = 116.40717
        locType = 61
        speed = 3.6f
        radius = 25f
        coorType = "bd09ll"
        province = "北京市"
        city = "北京"
        district = "东城区"
        street = "长安街"
        streetNumber = "1号"
        poiList = listOf(Poi("王府井"), Poi("故宫"))
    }

    /** Cross-checks a delivered fix against the evidence file rather than against the port's own output. */
    private fun rowFromGolden(family: String, inputText: String): Coord {
        val line = File(locateRepoRoot(), "recovered/geo_golden_156.txt").readLines()
            .first { it.trim().startsWith("$family($inputText)") }
        val values = line.substringAfter('=').trim().split(',')
        return Coord(values[0].toDouble(), values[1].toDouble(), CoordSystem.WGS84)
    }

    private fun assertNotEqualsRow(a: Coord, b: Coord) {
        assertFalse(
            abs(a.lat - b.lat) < COORD_TOLERANCE && abs(a.lng - b.lng) < COORD_TOLERANCE,
            "the fix equals the golden $b row, so the datum path was not distinguished",
        )
    }

    private fun locateRepoRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "recovered/geo_golden_156.txt").isFile) return directory
            directory = directory.parentFile
        }
        throw IllegalStateException("recovered/geo_golden_156.txt is not reachable from the test directory")
    }

    /** Collects what the provider pushes to a [LocationListener]. */
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

    /**
     * The production binding, except that the adapter must be able to reach a client instance the test can
     * inspect: `LocationClient` has no registry and `BaiduLocationProvider.client` is private, so the double
     * is injected here rather than constructed. Nothing else is overridden -- `buildOption`, `readFix`,
     * `setAgreePrivacy`, `setKey`, `makeListener`, the four client calls and the whole reflection helper
     * layer are production code, which is what makes the counters and the option object below worth reading.
     * [an unmodified BaiduSdk creates, configures and starts the client by itself] is the same path with the
     * injection removed.
     */
    private open class BindingSdk(private val injected: LocationClient) : BaiduSdk() {
        override fun createClient(context: Context): Any = injected
    }

    /**
     * Pretends the AAR is absent, which is the state this repository is really in. Only the two presence
     * properties are overridden, with the wording `classOrNull` itself uses (`BaiduSdk.kt:279-284`), so the
     * provider's failure detail is the production one; the bindings are never reached because `prepare()`
     * stops at `isPresent`.
     */
    private class MissingSdk : BaiduSdk() {
        override val isPresent: Boolean get() = false
        override val missingReason: String
            get() = "找不到 com.baidu.location.LocationClient：本 build 未包含百度定位 AAR"
    }

    private class StubProvider(override val id: String) : LocationProvider {
        override val capabilities = LocationCapabilities(
            nativeCoordSystem = CoordSystem.WGS84,
            inlinePlace = false,
            poiList = false,
            needsVendorKey = false,
            supportsRuntimeInterval = true,
        )
        override val isInstalled: Boolean get() = true
        override fun start(request: LocationRequest, listener: LocationListener) = true
        override fun update(request: LocationRequest) = true
        override fun stop() = Unit
        override suspend fun singleFix(request: LocationRequest): LocationResult =
            LocationResult.Failed(LocationFault(LocationFailure.TIMEOUT))
        override fun lastFix(): LocationFix? = null
        override fun close() = Unit
    }

    companion object {
        /** `LocationClient.setKey` argument from `manager/x0.java:676`, identical in both shipped versions. */
        private const val BAIDU_AK = "ubL50e1vplPn3FsVVbDfQjAcgnU0eZ7P"

        /** The golden file prints `%.9f`. */
        private const val COORD_TOLERANCE = 1e-9
    }
}
