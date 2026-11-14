package com.youloft.watcher.data

import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import com.youloft.linkup.api.dto.CallRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 通话记录域的上报器（`api/phone/call_record/report`，DESIGN 五点三十九）：被守护端把"上次同步
 * 以来的新通话"批量报上来，守护端的通话记录卡从 `api/index` 读台账。
 *
 * 门是三重的，与其他报表域一致：隐私已同意（挂 onSignedIn 即隐含）+ 已登录 + READ_CALL_LOG 已
 * 授权——没授权就静默跳过，不请求不提示（请求入口在权限列表屏）。
 *
 * 结构照 PhoneStateReporter 的 seam 模式：时间戳存取、CallLog 查询、上报三件事都是 lambda，
 * JVM 测试用记录替身就能把 delta 语义钉住；[CallLogReader] 是唯一碰 ContentResolver 的薄胶，
 * 行映射（[callRecordFromCursor]）用 MatrixCursor 可测。
 */
class CallRecordReporter(
    private val scope: CoroutineScope,
    private val lastTime: () -> Long,
    private val saveLastTime: (Long) -> Unit,
    private val granted: () -> Boolean,
    private val queryNew: (sinceSeconds: Long) -> List<CallRecord>,
    private val report: suspend (records: List<CallRecord>) -> Boolean,
) {
    /** One delta sync. Failures keep the old watermark — the next sync re-reports, the ledger dedupes. */
    fun reportOnce() {
        if (!granted()) return
        scope.launch {
            runCatching {
                val since = lastTime()
                val records = queryNew(since)
                if (records.isEmpty()) return@runCatching
                if (report(records)) {
                    saveLastTime(records.maxOf { it.time })
                }
            }
        }
    }

    companion object {
        /** The wire payload: `bean/CallRecord` fields verbatim, one map per call. */
        fun buildPayload(records: List<CallRecord>): MutableMap<String, Any?> = mutableMapOf(
            "records" to records.map {
                mapOf(
                    "contact" to it.contact,
                    "duration" to it.duration,
                    "isIncoming" to it.isIncoming,
                    "phone" to it.phone,
                    "time" to it.time,
                )
            },
        )
    }
}

/**
 * The one place that touches ContentResolver: the newest [limit] calls at or after [sinceSeconds]
 * (epoch seconds; 0 = no watermark yet → the newest batch only, not the whole history).
 */
class CallLogReader(private val context: Context, private val limit: Int = 100) {
    fun read(sinceSeconds: Long): List<CallRecord> {
        val selection = if (sinceSeconds > 0) "${CallLog.Calls.DATE} >= ?" else null
        val args = if (sinceSeconds > 0) arrayOf((sinceSeconds * 1000).toString()) else null
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.TYPE,
                ),
                selection,
                args,
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        callRecordFromCursor(cursor)?.let { add(it) }
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }
}

/**
 * Cursor row → the recovered bean. `isIncoming` follows the dialer's own vocabulary: OUTGOING is the
 * outgoing kind, everything else (incoming, missed, rejected…) rides the incoming side — the original
 * bean has no third direction, and a missed call is an incoming one the owner did not take.
 */
fun callRecordFromCursor(cursor: Cursor): CallRecord? = runCatching {
    val number = cursor.string(CallLog.Calls.NUMBER)
    val type = cursor.int(CallLog.Calls.TYPE)
    CallRecord(
        contact = cursor.string(CallLog.Calls.CACHED_NAME),
        duration = cursor.long(CallLog.Calls.DURATION),
        isIncoming = type != CallLog.Calls.OUTGOING_TYPE,
        phone = number,
        time = cursor.long(CallLog.Calls.DATE) / 1000,
    )
}.getOrNull()

private fun Cursor.string(column: String): String? =
    getColumnIndex(column).takeIf { it >= 0 }?.let { getString(it) }

private fun Cursor.long(column: String): Long =
    getColumnIndex(column).takeIf { it >= 0 }?.let { getLong(it) } ?: 0L

private fun Cursor.int(column: String): Int =
    getColumnIndex(column).takeIf { it >= 0 }?.let { getInt(it) } ?: 0
