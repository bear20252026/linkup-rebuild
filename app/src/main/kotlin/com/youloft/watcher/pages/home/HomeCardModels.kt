package com.youloft.watcher.pages.home

import com.youloft.linkup.api.dto.FriendLocation
import com.youloft.linkup.api.dto.HomeData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 守护 Tab 功能卡的内容装配（`api/index` HomeData → 卡片行）。纯函数放在 JVM 可测的一层：
 * 空数据（对方从未上报/无定位）产出"如实缺省"行，不编造数字。
 *
 * 原版面板由 view/home/Card*View 家族渲染，卡片种类与读数由服务端 HomeData 驱动；本重建渲染
 * 其中已接线的五张（对方手机状态/对方位置/**通话记录**/锁机/我的设备），应用使用卡在报表路由
 * 供数前保持缺席（原卡也是空数据时收起，DESIGN 五点三十三）。
 */
object HomeCards {

    /** One rendered card: a heading, a highlighted value, and up to two detail lines. */
    data class Card(
        val title: String,
        val value: String,
        val line1: String,
        val line2: String,
        val kind: Kind,
    )

    enum class Kind { PHONE_STATE, LOCATION, CALL_RECORD, LOCK, OWN_DEVICE }

    fun fromData(
        data: HomeData?,
        texts: Texts,
        remainingOf: (lockDurationSeconds: Long, nowEpochMillis: Long) -> String,
    ): List<Card> {
        if (data == null) return emptyList()
        val cards = mutableListOf<Card>()

        data.friendState?.let { state ->
            cards += Card(
                title = texts.phoneStatus,
                value = if (state.isCharging) texts.charging else battery(state.currentBattery),
                line1 = network(state.networkType, texts),
                line2 = state.screenTime.takeIf { it > 0 }
                    ?.let { texts.screenTime(formatDuration(it)) }
                    ?: texts.offline,
                kind = Kind.PHONE_STATE,
            )
        }
        data.friendLocation?.let { location ->
            cards += Card(
                title = texts.location,
                value = addressLine(location) ?: texts.noAddress,
                line1 = texts.arrivedAt(formatEpochSeconds(location.arrivalTime)),
                line2 = "",
                kind = Kind.LOCATION,
            )
        }
        // 通话记录卡（通话记录域，DESIGN 五点三十九）：只在有记录时渲染——没有就缺席，与原版
        // "空数据时收起"同一语义。最新一通是谁/什么方向/多久，行二是批次的数量摘要。
        data.callRecordList?.orEmpty()?.filterNotNull()?.takeIf { it.isNotEmpty() }?.let { records ->
            val latest = records.first()
            cards += Card(
                title = texts.callRecord,
                value = whoOf(latest, texts),
                line1 = callDirection(latest, texts) + durationSuffix(latest),
                line2 = texts.callSummary(records.size),
                kind = Kind.CALL_RECORD,
            )
        }
        data.lockInfo?.let { lock ->
            val locked = lock.lockTime > 0
            val remaining = if (locked && lock.lockDuration > 0) {
                val end = (lock.lockTime + lock.lockDuration) * 1000L
                remainingOf(lock.lockDuration, end)
            } else ""
            cards += Card(
                title = texts.lock,
                value = if (locked) lock.lockTitle?.takeIf { it.isNotBlank() } ?: texts.lock else texts.lockOff,
                line1 = if (locked && remaining.isNotEmpty()) texts.lockRemaining(remaining) else "",
                line2 = "",
                kind = Kind.LOCK,
            )
        }
        data.phoneInfoCard?.let { self ->
            cards += Card(
                title = texts.ownDevice,
                value = battery(self.currentBattery),
                line1 = listOfNotNull(
                    self.model?.takeIf { it.isNotBlank() },
                    self.osVersion?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                line2 = network(self.networkType, texts),
                kind = Kind.OWN_DEVICE,
            )
        }
        return cards
    }

    /**
     * 定位卡文案：`Address` bean 没有整串地址字段（原版 getAddressStr 在服务端拼），客户端把
     * poiName → 街道门牌 → 区 → 市的已知段拼出来；全空答 null（卡面显示"暂无定位"）。
     */
    fun addressLine(location: FriendLocation): String? {
        val address = location.address ?: return null
        val street = listOfNotNull(address.street, address.streetNumber).joinToString("")
        val parts = listOfNotNull(
            address.poiName?.takeIf { it.isNotBlank() },
            street.takeIf { it.isNotBlank() },
            address.district?.takeIf { it.isNotBlank() },
            address.city?.takeIf { it.isNotBlank() },
        )
        return parts.joinToString(" · ").takeIf { it.isNotBlank() }?.take(24)
    }

    /** 通话记录卡的对象行：联系人优先，退回号码，都没有如实答"未知号码"。 */
    private fun whoOf(record: com.youloft.linkup.api.dto.CallRecord, texts: Texts): String =
        record.contact?.takeIf { it.isNotBlank() }
            ?: record.phone?.takeIf { it.isNotBlank() }
            ?: texts.unknownNumber

    /** 方向词：未接（来电未接通）/ 来电 / 去电——原版 bean 只有两个方向，未接是没接通的来电。 */
    private fun callDirection(record: com.youloft.linkup.api.dto.CallRecord, texts: Texts): String = when {
        record.isIncoming && record.duration <= 0 -> texts.callMissed
        record.isIncoming -> texts.callIncoming
        else -> texts.callOutgoing
    }

    private fun durationSuffix(record: com.youloft.linkup.api.dto.CallRecord): String =
        if (record.duration > 0) " · " + formatDuration(record.duration) else ""

    private fun battery(percent: Int): String = "$percent%"

    private fun network(type: Int, texts: Texts): String = when (type) {
        1 -> texts.networkWifi
        2 -> texts.networkCellular
        else -> texts.networkNone
    }

    /** Screen-time seconds as `1小时23分`；不足一分钟按 0 分。 */
    fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}小时${minutes}分"
            else -> "${minutes}分"
        }
    }

    /** 锁机剩余时长：以结束时刻对表（发起端与收端的钟不必一致），到期答 0 分钟。 */
    object Remaining {
        fun format(durationSeconds: Long, endEpochMillis: Long): String {
            val left = (endEpochMillis - System.currentTimeMillis()) / 1000
            if (left <= 0) return "0分钟"
            return formatDuration(left)
        }
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)

    fun formatEpochSeconds(seconds: Long): String =
        if (seconds <= 0) "--:--" else synchronized(timeFormat) { timeFormat.format(Date(seconds * 1000)) }

    /** The card strings a fragment supplies from resources; lambdas keep the format specifiers. */
    interface Texts {
        val phoneStatus: String
        val charging: String
        val networkWifi: String
        val networkCellular: String
        val networkNone: String
        val screenTime: (String) -> String
        val offline: String
        val location: String
        val noAddress: String
        val arrivedAt: (String) -> String
        val callRecord: String
        val callIncoming: String
        val callOutgoing: String
        val callMissed: String
        val callSummary: (Int) -> String
        val unknownNumber: String
        val lock: String
        val lockOff: String
        val lockRemaining: (String) -> String
        val ownDevice: String
    }
}
