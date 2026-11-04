package com.youloft.linkup.net

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The config client is pinned against **a response captured from our own live service on 2026-11-04**
 * (`config_getconfigs_live.json`, saved next to this test), because the interesting part is not the
 * HTTP call — it is that every value arrives base64 and the channel list lives one level deeper, in
 * `msg.Parameter_Map`.
 */
class ConfigServiceTest {

    private val service = ConfigService()

    private fun liveSample(): String =
        assertNotNull(javaClass.classLoader?.getResourceAsStream("config_getconfigs_live.json"))
            .readBytes().toString(Charsets.UTF_8)

    @Test
    fun `the captured live response decodes into a parameter map`() {
        val fetch = assertNotNull(service.parse(liveSample()))

        val parameters = assertNotNull(fetch.values[ConfigService.PARAMETER_MAP])
        assertTrue("showPermissionChannel" in parameters)
        assertEquals(18, fetch.parameterMap.entrySet().size, "the live map has 18 keys as observed")
    }

    @Test
    fun `the live channel list carries the wildcard the 156 client matched on`() {
        val fetch = assertNotNull(service.parse(liveSample()))

        assertEquals(
            listOf("3300", "3301", "-1"),
            service.stringList(fetch, "showPermissionChannel"),
            "with -1 in the list, the 1.5.6 rule opens the consent sheet for every channel",
        )
    }

    @Test
    fun `other live values keep their own types`() {
        val fetch = assertNotNull(service.parse(liveSample()))

        assertEquals("true", fetch.parameterMap.get("registerAfterShowPermission").asString)
        assertEquals("100", fetch.parameterMap.get("enableAniWhenMarkerMoveDistance").asString)
    }

    @Test
    fun `a missing key reads as an empty list instead of throwing`() {
        val fetch = assertNotNull(service.parse(liveSample()))

        assertEquals(emptyList(), service.stringList(fetch, "notAKeyAtAll"))
    }

    @Test
    fun `a value that is not base64 is kept as-is and a bad envelope reads as no answer`() {
        assertEquals("plain", service.decodeValue("plain"))
        assertEquals("", service.decodeValue(""))

        assertNull(service.parse("""{"status":0,"msg":{}}"""))
        assertNull(service.parse("""{"status":1}"""))
        assertNull(service.parse("not json"))
    }

    @Test
    fun `the request url is the recovered triple of parameters`() {
        assertEquals(
            "https://configsys.51wnl-cq.com/api/config/getconfigs?appid=android-watcher&appver=166&lastupdate=42",
            service.url(lastUpdate = "42"),
        )
    }

    @Test
    fun `the device identity params the recovered interceptor appended go on the same query`() {
        val withParams = ConfigService(
            params = mapOf(
                "bd" to "com.youloft.watcher",
                "cid" to ConfigService.CLIENT_ID,
                "av" to "1.6.6",
                "chn" to "3301-0",
                "ov" to "13",
            ),
        )

        val url = withParams.url()
        assertTrue(url.contains("&bd=com.youloft.watcher"), url)
        assertTrue(url.contains("&cid=Youloft_Android"), url)
        assertTrue(url.contains("&chn=3301-0"), url)
        assertTrue(url.contains("&ov=13"), url)
    }

    @Test
    fun `the answer's r is the token to echo, and an empty msg still counts as an answer`() {
        val fetch = assertNotNull(service.parse(liveSample()))
        assertEquals("17533440860101", fetch.lastUpdate, "measured: asking with this token answers msg:{}")

        val unchanged = assertNotNull(service.parse("""{"msg":{},"status":1,"r":99,"rver":"166_99"}"""))
        assertEquals(emptyMap(), unchanged.values)
        assertEquals("99", unchanged.lastUpdate)
    }

    @Test
    fun `the throttle is the original pair of clauses`() {
        // Same app version: only re-ask past the ten-minute window (of.b.o returned while inside it).
        assertTrue(service.shouldFetch("166", lastRequestAtMillis = 0L, nowMillis = 600_000L))
        assertFalse(service.shouldFetch("166", lastRequestAtMillis = 100L, nowMillis = 599_999L))
        // Different app version: always re-ask, and drop the token so the server sends everything.
        assertTrue(service.shouldFetch("156", lastRequestAtMillis = 100L, nowMillis = 100L))
        assertEquals("", service.lastUpdateFor("156", "17533440860101"))
        assertEquals("17533440860101", service.lastUpdateFor("166", "17533440860101"))
        assertEquals("", service.lastUpdateFor("166", null))
    }

    @Test
    fun `the ten minute window is the original number`() {
        assertTrue(service.isStale(lastFetchAtMillis = 0L, nowMillis = ConfigService.REFRESH_INTERVAL_MILLIS))
        assertFalse(service.isStale(lastFetchAtMillis = 100L, nowMillis = 100L + 599_999L))
    }
}
