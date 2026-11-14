package com.youloft.watcher.data

import com.youloft.linkup.api.dto.AppUsage
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test

/**
 * 应用使用域的客户端规则（DESIGN 五点四十）：聚合器把前台事件配对成时长（窗口两端截断、不足
 * 一秒丢弃、秒落 bean）、总量口径的 payload 原样、未授权与空批次静默。聚合器纯函数 JVM 钉住；
 * UsageStatsManager 查询胶是真机待验（待验 88）。
 */
class AppUsageReporterTest {

    private fun sample(pkg: String, type: Int, ts: Long) = UsageEventSample(pkg, type, ts)

    @Test
    fun `resumed and paused pairs become durations, sorted biggest first`() {
        val entries = AppUsageAggregator.aggregate(
            samples = listOf(
                sample("com.game", 1, 0L), // RESUMED
                sample("com.game", 2, 3_600_000L), // PAUSED after an hour
                sample("com.chat", 1, 3_600_000L),
                sample("com.chat", 2, 4_200_000L), // 用满 600 秒,不能被碎片规则吃掉
            ),
            windowStartMs = 0L,
            windowEndMs = 10_000_000L,
        )

        assertEquals(2, entries.size)
        assertEquals("com.game", entries[0].packageName)
        assertEquals(3600L, entries[0].duration, "an hour in foreground lands as 3600 seconds")
        assertEquals(1, entries[0].count)
        assertEquals(600L, entries[1].duration)
        assertEquals(1, entries[1].statisticsType, "today's window kind")
    }

    @Test
    fun `a session still open at the window end clamps to it`() {
        val entries = AppUsageAggregator.aggregate(
            samples = listOf(sample("com.game", 1, 0L)),
            windowStartMs = 0L,
            windowEndMs = 600_000L,
        )
        assertEquals(600L, entries.single().duration, "the report carries totals so far")
        assertEquals(1, entries.single().count)
    }

    @Test
    fun `fragments under a second are dropped and re-resumed pairs re-open cleanly`() {
        val entries = AppUsageAggregator.aggregate(
            samples = listOf(
                sample("com.game", 1, 0L),
                sample("com.game", 2, 500L), // half a second: dropped
                sample("com.game", 1, 1000L),
                sample("com.game", 2, 61_000L), // one real minute
            ),
            windowStartMs = 0L,
            windowEndMs = 120_000L,
        )
        assertEquals(1, entries.size)
        assertEquals(60L, entries.single().duration, "500ms fragment dropped, the real minute kept")
        assertEquals(2, entries.single().count, "both resumes counted")
    }

    @Test
    fun `events outside the window clamp inside it`() {
        val entries = AppUsageAggregator.aggregate(
            samples = listOf(sample("com.game", 1, -500_000L), sample("com.game", 2, 900_000L)),
            windowStartMs = 0L,
            windowEndMs = 600_000L,
        )
        assertEquals(600L, entries.single().duration, "begin and end clamp to the window")
        assertEquals(0L, entries[0].lastUseTime, "lastUseTime clamps into the window: the session began before it, so the window start is the honest answer")
    }

    @Test
    fun `the reporter skips silently when denied or when the day has no usage`() {
        var queried = 0
        val denied = AppUsageReporter(
            scope = CoroutineScope(Dispatchers.Unconfined),
            granted = { false },
            dayStart = { 1_000L },
            queryNew = { queried++; emptyList() },
            report = { _, _ -> true },
        )
        denied.reportOnce()
        assertEquals(0, queried, "denied: not even a query")

        var reported = 0
        val idle = AppUsageReporter(
            scope = CoroutineScope(Dispatchers.Unconfined),
            granted = { true },
            dayStart = { 1_000L },
            queryNew = { queried++; emptyList() },
            report = { _, _ -> reported++; true },
        )
        idle.reportOnce()
        assertEquals(1, queried)
        assertEquals(0, reported, "no usage today → no report")
    }

    @Test
    fun `the reporter answers with the entries and the guarded phone's day`() {
        var seen: Pair<Long, List<AppUsage>>? = null
        val reporter = AppUsageReporter(
            scope = CoroutineScope(Dispatchers.Unconfined),
            granted = { true },
            dayStart = { 1_760_000_000L },
            queryNew = { listOf(AppUsage(packageName = "com.game", appName = "游戏", duration = 3600, count = 4)) },
            report = { entries, day ->
                seen = day to entries
                true
            },
        )

        reporter.reportOnce()

        val (day, entries) = seen ?: throw AssertionError("the report went out")
        assertEquals(1_760_000_000L, day)
        assertTrue(entries.single().duration > 0)
    }

    @Test
    fun `the wire payload carries the day and the bean fields verbatim`() {
        val payload = AppUsageReporter.buildPayload(
            listOf(AppUsage(packageName = "com.game", appName = "游戏", duration = 3600, count = 4, lastUseTime = 1_760_000_500)),
            day = 1_760_000_000L,
        )
        assertEquals(1_760_000_000L, payload["day"])
        val stats = payload["stats"] as List<*>
        val row = stats.single() as Map<*, *>
        assertEquals<Any?>("com.game", row["packageName"])
        assertEquals<Any?>("游戏", row["appName"])
        assertEquals<Any?>(3600L, row["duration"])
        assertEquals<Any?>(4, row["count"])
        assertEquals<Any?>(1_760_000_500L, row["lastUseTime"])
    }
}
