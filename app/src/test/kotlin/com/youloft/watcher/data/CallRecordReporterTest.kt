package com.youloft.watcher.data

import android.database.MatrixCursor
import android.provider.CallLog
import com.youloft.linkup.api.dto.CallRecord
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 通话记录域的客户端规则（DESIGN 五点三十九）：delta 水位语义（成功才推进，失败重报——服务端
 * 台账去重）、未授权静默跳过、空批次零动作、Cursor 行映射（OUTGOING 之外都算来电方向）。
 * [callRecordFromCursor] 用 MatrixCursor 跑真实映射代码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallRecordReporterTest {

    private fun unconfined() = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `a successful report pushes the watermark to the newest call`() {
        var lastTime = 100L
        val reported = mutableListOf<CallRecord>()
        val reporter = CallRecordReporter(
            scope = unconfined(),
            lastTime = { lastTime },
            saveLastTime = { lastTime = it },
            granted = { true },
            queryNew = { since ->
                assertEquals(100L, since)
                listOf(CallRecord(phone = "13800000001", time = 300, isIncoming = true, duration = 60))
            },
            report = {
                reported += it
                true
            },
        )

        reporter.reportOnce()

        assertEquals(1, reported.size)
        assertEquals(300L, lastTime, "the watermark lands on the newest call")
    }

    @Test
    fun `a failed report keeps the watermark so the ledger dedupes the retry`() {
        var lastTime = 100L
        val reporter = CallRecordReporter(
            scope = unconfined(),
            lastTime = { lastTime },
            saveLastTime = { lastTime = it },
            granted = { true },
            queryNew = { listOf(CallRecord(phone = "13800000001", time = 300)) },
            report = { false },
        )

        reporter.reportOnce()

        assertEquals(100L, lastTime, "failure does not advance the watermark; the next sync re-reports")
    }

    @Test
    fun `no permission is a silent skip and an empty delta does nothing`() {
        var queried = 0
        val denied = CallRecordReporter(
            scope = unconfined(),
            lastTime = { 0L },
            saveLastTime = {},
            granted = { false },
            queryNew = { queried++; emptyList() },
            report = { true },
        )
        denied.reportOnce()
        assertEquals(0, queried, "denied: not even a query")

        var reported = 0
        val idle = CallRecordReporter(
            scope = unconfined(),
            lastTime = { 0L },
            saveLastTime = {},
            granted = { true },
            queryNew = { queried++; emptyList() },
            report = { reported++; true },
        )
        idle.reportOnce()
        assertEquals(1, queried)
        assertEquals(0, reported, "an empty delta makes no report")
    }

    @Test
    fun `cursor rows map onto the recovered bean with the dialer's own direction rule`() {
        val cursor = MatrixCursor(
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE,
            ),
        )
        cursor.addRow(arrayOf<Any>("13800000001", "妈妈", 1_700_000_000_000, 60L, CallLog.Calls.INCOMING_TYPE))
        cursor.addRow(arrayOf<Any?>("13800000002", null, 1_700_000_100_000, 30L, CallLog.Calls.OUTGOING_TYPE))
        cursor.addRow(arrayOf<Any>("13800000003", "爸爸", 1_700_000_200_000, 0L, CallLog.Calls.MISSED_TYPE))

        val reader = buildList {
            while (cursor.moveToNext()) callRecordFromCursor(cursor)?.let { add(it) }
        }

        assertEquals(3, reader.size)
        assertEquals("妈妈", reader[0].contact)
        assertEquals(true, reader[0].isIncoming)
        assertEquals(1_700_000_000L, reader[0].time, "the dialer's millis collapse to seconds")
        assertEquals(false, reader[1].isIncoming, "OUTGOING is the outgoing kind")
        assertNull(reader[1].contact)
        assertEquals(true, reader[2].isIncoming, "a missed call rides the incoming side")
        assertEquals(0L, reader[2].duration)
    }

    @Test
    fun `the wire payload carries the bean fields verbatim`() {
        val payload = CallRecordReporter.buildPayload(
            listOf(CallRecord(contact = "妈妈", phone = "13800000001", duration = 60, isIncoming = true, time = 100)),
        )
        val records = payload["records"] as List<*>
        val row = records.single() as Map<*, *>
        assertEquals<Any?>("妈妈", row["contact"])
        assertEquals<Any?>(60L, row["duration"])
        assertEquals<Any?>(true, row["isIncoming"])
        assertEquals<Any?>("13800000001", row["phone"])
        assertEquals<Any?>(100L, row["time"])
    }
}
