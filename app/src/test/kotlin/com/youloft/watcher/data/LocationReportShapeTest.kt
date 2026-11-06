package com.youloft.watcher.data

import com.google.gson.Gson
import com.youloft.linkup.api.dto.Location
import com.youloft.linkup.location.Coord
import com.youloft.linkup.location.CoordSystem
import com.youloft.linkup.location.LocationFix
import com.youloft.linkup.location.Place
import com.youloft.linkup.location.LocationTuning
import com.youloft.linkup.location.ReportKind
import com.youloft.linkup.net.ApiResponse
import com.youloft.linkup.net.LinkUpApi
import com.youloft.watcher.data.local.InMemoryKeyValueStores
import com.youloft.watcher.data.local.StoredRemoteConfig
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The wire side of the location layer: what `:location` hands over, and what actually leaves the phone.
 *
 * These assertions exist because the two halves are verified separately elsewhere — `:location` proves the
 * neutral model, `server/` proves the decoder — and nothing else would notice if the mapping between them
 * drifted by one key or one datum. `gatewayVerify` covers the live round trip; this covers the shape
 * without a server.
 */
class LocationReportShapeTest {

    private val fix = LocationFix(
        coord = Coord(39.896937039, 116.394545721, CoordSystem.WGS84),
        epochSeconds = 1_730_000_000L,
        accuracyMeters = 12.5f,
        speedMetersPerSecond = 1.0f,
        place = Place(
            province = "北京市",
            city = "北京",
            district = "东城区",
            street = "长安街",
            streetNumber = "1号",
            poiName = "某大厦",
        ),
        vendorFixType = 61,
        providerId = "baidu",
    )

    @Test
    fun `the report body carries exactly the keys the gateway decodes`() {
        val json = Gson().toJson(fix.toWireLocation(ReportKind.UNCLASSIFIED))
        val keys = topLevelKeys(json)
        // `server/internal/location/location.go:41-51` decodes these nine and nothing else; `imPush` is a
        // client-side stay counter that Gson drops while null, exactly as the recovered app dropped it.
        assertEquals(
            setOf("accuracy", "address", "id", "lat", "lng", "locType", "speed", "timePeriod", "type"),
            keys,
            "wire keys drifted: $json",
        )
        val address = body(json)["address"] as Map<*, *>
        assertEquals(
            setOf("province", "city", "district", "street", "streetNumber", "poiName"),
            address.keys,
            "bean/Address has no full-address field; the server composes the text from these six",
        )
    }

    @Test
    fun `an absent address drops the key rather than sending null`() {
        val json = Gson().toJson(fix.copy(place = null).toWireLocation(ReportKind.UNCLASSIFIED))
        val keys = topLevelKeys(json)
        assertTrue("address" !in keys, "serializeNulls is off in the recovered client too: $json")
    }

    @Test
    fun `the coordinate that leaves is WGS84, not the vendor datum`() {
        val wire = fix.toWireLocation(ReportKind.UNCLASSIFIED)
        assertEquals(39.896937039, wire.lat, 1e-9)
        assertEquals(116.394545721, wire.lng, 1e-9)
        // The same point in Baidu's own datum is ~500 m away; sending it would put every track row next to
        // a different address than the one in the same body.
        val bd09 = fix.coord.asBd09()
        assertTrue(kotlin.math.abs(bd09.lat - wire.lat) > 0.001, "expected a visible datum difference, got $bd09")
    }

    @Test
    fun `report kinds land on the type numbers the recovered app wrote`() {
        assertEquals(0, fix.toWireLocation(ReportKind.UNCLASSIFIED).type)
        assertEquals(2, fix.toWireLocation(ReportKind.STAY).type)
        assertEquals(3, fix.toWireLocation(ReportKind.LEAVE).type)
        assertEquals(4, fix.toWireLocation(ReportKind.UNCHANGED).type)
        assertEquals(61, fix.toWireLocation(ReportKind.STAY).locType, "BDLocation.getLocType() is forwarded")
        assertEquals(0L, fix.toWireLocation(ReportKind.STAY).id, "the Room id is not ours to invent")
    }

    @Test
    fun `a refused envelope is thrown so the collector can keep the fix`() {
        val gateway = LinkUpLocationGateway(apiReturning(ApiResponse<Location>(status = 1001, msg = "参数异常")))
        val failure = assertFailsWith<LocationReportException> {
            runBlocking { gateway.report(fix, ReportKind.UNCLASSIFIED) }
        }
        assertEquals(1001, failure.status)
        assertEquals("参数异常", failure.message)
    }

    @Test
    fun `a successful report sends the bean and raises nothing`() {
        val sent = mutableListOf<Location>()
        val gateway = LinkUpLocationGateway(apiReturning(ApiResponse<Location>(status = 200, msg = "ok"), sent))
        runBlocking { gateway.report(fix, ReportKind.UNCLASSIFIED) }
        val body = sent.single()
        assertEquals(fix.coord.lat, body.lat, 1e-9, "the fix is sent as-is, not re-read from the vendor SDK")
        assertEquals(1_730_000_000L, body.timePeriod, "epoch seconds, which is how the server buckets days")
        assertEquals("某大厦", body.address?.poiName)
    }

    @Test
    fun `config numbers override the recovered defaults only when the key is there`() {
        val config = StoredRemoteConfig(
            InMemoryKeyValueStores(
                mapOf(
                    // Both spellings occur: `of.b` base64-decodes values, so some arrive quoted.
                    StoredRemoteConfig.KEY_PARAMETER_MAP to
                        """{"locationScanMillsWhenDormancy":60000,"locationMaxInterval":"30","lowSpeedThreshold":5.0}""",
                ),
            ),
        )
        assertEquals(60000.0, config.number("locationScanMillsWhenDormancy") ?: 0.0)
        assertEquals(30.0, config.number("locationMaxInterval") ?: 0.0, "a quoted number still reads")
        assertEquals(5.0, config.number("lowSpeedThreshold") ?: 0.0)
        assertEquals(null, config.number("notStored"), "absent is not zero")

        val tuning = config.locationTuning()
        assertEquals(60_000L, tuning.dormancyIntervalMillis)
        assertEquals(30L, tuning.maxIntervalSeconds)
        // `manager/l.java:64-80` defaults survive for anything the config does not carry: the interval floor
        // reading as 0 would mean reporting every single fix.
        assertEquals(5L, tuning.minIntervalSeconds)
        assertEquals(40.0, tuning.minUploadDistanceMeters)
    }

    @Test
    fun `cadence values are the original formula, not a re-derivation`() {
        // manager/x0.java:156-158 with the shipped defaults.
        val tuning = LocationTuning()
        assertEquals(20.0, tuning.cadenceSeconds(0f), "stopped -> ceiling")
        assertEquals(20.0, tuning.cadenceSeconds(1f), "40/1 hits the ceiling")
        assertEquals(10.0, tuning.cadenceSeconds(4f), "40/4")
        assertEquals(5.0, tuning.cadenceSeconds(64f), "floor, not 0.625")
    }

    @Suppress("UNCHECKED_CAST")
    private fun body(json: String): Map<String, Any?> = Gson().fromJson(json, Map::class.java) as Map<String, Any?>

    private fun topLevelKeys(json: String): Set<String> = body(json).keys

    /** A [LinkUpApi] that answers one canned response, so the gateway's own logic is what is under test. */
    private fun apiReturning(response: ApiResponse<*>, captured: MutableList<Location> = mutableListOf()): LinkUpApi {
        val proxy = Proxy.newProxyInstance(
            LinkUpApi::class.java.classLoader,
            arrayOf(LinkUpApi::class.java),
        ) { _, method, args ->
            when (method.name) {
                "locationReport" -> {
                    (args?.firstOrNull() as? Location)?.let(captured::add)
                    ApiResponse(status = response.status, msg = response.msg, data = null)
                }

                "toString" -> "fake-linkup-api"
                "hashCode" -> 0
                "equals" -> false
                else -> error("unexpected call ${method.name}")
            }
        }
        return proxy as LinkUpApi
    }
}
