package com.youloft.watcher.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PhoneStateReporter` 的载荷形状：上报键就是服务端 `phoneStateReport` 读的键（含本工程搭车的
 * av/client 版本行），任何一端改名另一端静默归零——这里钉住。
 */
class PhoneStateReporterTest {

    @Test
    fun payloadCarriesFactsAndVersionRow() {
        val payload = PhoneStateReporter.buildPayload(
            mapOf(
                "currentBattery" to 66,
                "isCharging" to true,
                "networkType" to 1,
                "wifi" to "HomeWiFi",
                "osVersion" to "14",
                "model" to "Pixel 8",
                "brand" to "google",
            ),
        )
        assertEquals(66, payload["currentBattery"])
        assertEquals(true, payload["isCharging"]) // 服务端按 JSON bool 读（truthy）
        assertEquals(1, payload["networkType"])
        assertEquals("HomeWiFi", payload["wifi"])
        assertEquals("14", payload["osVersion"])
        assertEquals("Pixel 8", payload["model"])
        assertEquals("google", payload["brand"])
        // 版本行：av 是包的 versionName，client 是字面量 android（与 H5 查询串同口径）。
        assertEquals("1.6.6", payload["av"])
        assertEquals("android", payload["client"])
    }

    @Test
    fun payloadFallsBackToSafeZeroesForMissingFacts() {
        val payload = PhoneStateReporter.buildPayload(emptyMap())
        assertEquals(0, payload["currentBattery"])
        assertEquals(false, payload["isCharging"])
        assertEquals("", payload["wifi"])
    }

    @Test
    fun reportOnceDeliversAndNeverThrows() {
        val delivered = mutableListOf<Map<String, Any?>>()
        val reporter = PhoneStateReporter(CoroutineScope(Dispatchers.Unconfined)) { payload ->
            delivered += payload
            true
        }
        reporter.reportOnce { mapOf("currentBattery" to 10) }
        assertEquals(1, delivered.size)
        assertEquals(10, delivered.first()["currentBattery"])

        // 上报函数自己炸也不能带崩登录路径。
        val throwing = PhoneStateReporter(CoroutineScope(Dispatchers.Unconfined)) { throw IllegalStateException("offline") }
        throwing.reportOnce { mapOf("currentBattery" to 1) }
        assertTrue(true)
    }
}
