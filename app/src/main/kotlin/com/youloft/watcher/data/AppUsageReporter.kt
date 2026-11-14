package com.youloft.watcher.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.youloft.linkup.api.dto.AppUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 应用使用域的上报器（`api/phone/app_use/report`，DESIGN 五点四十）：被守护端把"今天的各应用
 * 使用总量"报上来（**总量口径**，服务端同键 replace——重报幂等，不需要水位），守护端的
 * 应用使用卡从 `api/index` 读。
 *
 * 门与其他报表域一致：隐私同意（挂 onSignedIn 即隐含）+ 已登录 + "使用情况访问权限"——这个
 * 权限不是运行时弹窗，是 Settings 里的 AppOp（权限列表屏已有跳转行），未授权静默跳过。
 */
class AppUsageReporter(
    private val scope: CoroutineScope,
    private val granted: () -> Boolean,
    private val dayStart: () -> Long,
    private val queryNew: (dayStartSeconds: Long) -> List<AppUsage>,
    private val report: suspend (entries: List<AppUsage>, day: Long) -> Boolean,
) {
    fun reportOnce() {
        if (!granted()) return
        scope.launch {
            runCatching {
                val day = dayStart()
                val entries = queryNew(day)
                if (entries.isEmpty()) return@runCatching
                report(entries, day)
            }
        }
    }

    companion object {
        /** The wire payload: `bean/AppUsage` fields verbatim plus the guarded phone's own day. */
        fun buildPayload(entries: List<AppUsage>, day: Long): MutableMap<String, Any?> = mutableMapOf(
            "day" to day,
            "stats" to entries.map {
                mapOf(
                    "packageName" to it.packageName,
                    "appName" to it.appName,
                    "duration" to it.duration,
                    "count" to it.count,
                    "lastUseTime" to it.lastUseTime,
                )
            },
        )
    }
}

/** 聚合器的中性事件样本——reader 从 UsageEvents 搬进来，聚合器不碰 SDK 类型。 */
data class UsageEventSample(val packageName: String, val eventType: Int, val timestampMs: Long)

/**
 * 前台时长聚合：RESUMED 开始、PAUSED 结束的配对（窗口两端截断），RESUMED 计数、最后一个
 * RESUMED 是 lastUseTime。输出 [AppUsage]，duration 以**秒**落 bean（原版口径），窗口内毫秒
 * 归零的应用不出现在结果里。纯函数，JVM 钉住。
 */
object AppUsageAggregator {

    fun aggregate(
        samples: List<UsageEventSample>,
        windowStartMs: Long,
        windowEndMs: Long,
        appNameOf: (String) -> String? = { null },
    ): List<AppUsage> {
        val ordered = samples.sortedBy { it.timestampMs }
        val open = HashMap<String, Long>()
        val durations = HashMap<String, Long>()
        val counts = HashMap<String, Int>()
        val lastUse = HashMap<String, Long>()

        fun close(pkg: String, at: Long) {
            val started = open.remove(pkg) ?: return
            val end = at.coerceAtMost(windowEndMs)
            val begin = started.coerceAtLeast(windowStartMs)
            if (end > begin) durations[pkg] = (durations[pkg] ?: 0L) + (end - begin)
        }

        for (sample in ordered) {
            when (sample.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    close(sample.packageName, sample.timestampMs)
                    open[sample.packageName] = sample.timestampMs
                    counts[sample.packageName] = (counts[sample.packageName] ?: 0) + 1
                    lastUse[sample.packageName] = sample.timestampMs.coerceAtLeast(windowStartMs)
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> close(sample.packageName, sample.timestampMs)
            }
        }
        // 窗口结束时还在前台的，按窗口终点截断。
        open.keys.toList().forEach { pkg -> close(pkg, windowEndMs) }

        return durations.entries
            .filter { it.value >= 1000 } // 不足一秒的碎片不进台账
            .map { (pkg, ms) ->
                AppUsage(
                    packageName = pkg,
                    appName = appNameOf(pkg),
                    duration = ms / 1000,
                    count = counts[pkg] ?: 0,
                    lastUseTime = (lastUse[pkg] ?: 0L) / 1000,
                    statisticsType = 1,
                )
            }
            .sortedByDescending { it.duration }
    }
}

/**
 * The one place that touches UsageStatsManager: today's events (the guarded phone's local day
 * start → now) become neutral samples, plus the app-label lookup the aggregator uses for display.
 */
class UsageStatsReader(private val context: Context) {

    /** "使用情况访问权限"是 AppOp：MODE_ALLOWED 才查，查询本身也可能在未授权时答空。 */
    fun granted(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun read(dayStartSeconds: Long): List<AppUsage> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val windowStart = dayStartSeconds * 1000
        val now = System.currentTimeMillis()
        if (now <= windowStart) return emptyList()
        val events = mutableListOf<UsageEventSample>()
        runCatching {
            val batch = usm.queryEvents(windowStart, now)
            while (batch.hasNextEvent()) {
                batch.getNextEvent(batchEvent)
                val pkg = batchEvent.packageName ?: continue
                when (batchEvent.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED ->
                        events += UsageEventSample(
                            packageName = pkg,
                            eventType = batchEvent.eventType,
                            timestampMs = batchEvent.timeStamp,
                        )
                }
            }
        }.getOrDefault(Unit)
        val labels = HashMap<String, String?>()
        val appNameOf: (String) -> String? = { pkg ->
            labels.getOrPut(pkg) {
                runCatching {
                    val pm = context.packageManager
                    pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString().takeIf { it.isNotBlank() }
                }.getOrNull()
            }
        }
        return AppUsageAggregator.aggregate(events, windowStart, now, appNameOf)
    }

    private val batchEvent = UsageEvents.Event()
}
