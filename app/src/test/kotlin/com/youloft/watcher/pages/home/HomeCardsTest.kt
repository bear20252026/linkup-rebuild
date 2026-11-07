package com.youloft.watcher.pages.home

import com.youloft.linkup.api.dto.FriendLocation
import com.youloft.linkup.api.dto.FriendState
import com.youloft.linkup.api.dto.HomeData
import com.youloft.linkup.api.dto.LockInfo
import com.youloft.linkup.api.dto.PhoneStateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 守护 Tab 功能卡的装配规则：有数据的卡渲染、没数据的卡缺席、不编造数字。 */
class HomeCardsTest {

    private val texts = object : HomeCards.Texts {
        override val phoneStatus = "对方手机状态"
        override val charging = "充电中"
        override val networkWifi = "WiFi 在线"
        override val networkCellular = "移动网络在线"
        override val networkNone = "无网络"
        override val screenTime: (String) -> String = { "亮屏 $it" }
        override val offline = "离线"
        override val location = "对方位置"
        override val noAddress = "暂无定位"
        override val arrivedAt: (String) -> String = { "到达于 $it" }
        override val lock = "锁机"
        override val lockOff = "未锁机"
        override val lockRemaining: (String) -> String = { "剩余 $it" }
        override val ownDevice = "我的设备"
    }

    private fun remainingFormatter(durationSeconds: Long, endEpochMillis: Long) =
        HomeCards.Remaining.format(durationSeconds, endEpochMillis)

    @Test
    fun nullDataRendersNoCards() {
        assertTrue(HomeCards.fromData(null, texts, ::remainingFormatter).isEmpty())
    }

    @Test
    fun phoneStateCardCarriesFacts() {
        val cards = HomeCards.fromData(
            HomeData(friendState = FriendState(currentBattery = 66, isCharging = true, networkType = 1, screenTime = 4980)),
            texts,
            ::remainingFormatter,
        )
        assertEquals(1, cards.size)
        val card = cards.first()
        assertEquals(HomeCards.Kind.PHONE_STATE, card.kind)
        assertEquals("对方手机状态", card.title)
        assertEquals("充电中", card.value)
        assertEquals("WiFi 在线", card.line1)
        assertEquals("亮屏 1小时23分", card.line2)
    }

    @Test
    fun zeroScreenTimeHonestOffense() {
        val cards = HomeCards.fromData(
            HomeData(friendState = FriendState(currentBattery = 10, networkType = 0)),
            texts,
            ::remainingFormatter,
        )
        // 未上报的键不编造：无网络 + 离线。
        assertEquals("无网络", cards.first().line1)
        assertEquals("离线", cards.first().line2)
    }

    @Test
    fun locationCardUsesAddressAndArrival() {
        val cards = HomeCards.fromData(
            HomeData(
                friendLocation = FriendLocation(
                    address = null,
                    arrivalTime = 1760000000,
                ),
            ),
            texts,
            ::remainingFormatter,
        )
        assertEquals(HomeCards.Kind.LOCATION, cards.first().kind)
        assertEquals("暂无定位", cards.first().value)
        assertTrue(cards.first().line1.startsWith("到达于 "))
    }

    @Test
    fun lockCardShowsRemainingWhenDurationKnown() {
        val future = System.currentTimeMillis() / 1000 + 2 * 3600 + 5 * 60
        val cards = HomeCards.fromData(
            HomeData(lockInfo = LockInfo(lockTime = future - 7200, lockDuration = 7200 + 300, lockTitle = "写作业")),
            texts,
            ::remainingFormatter,
        )
        val card = cards.first()
        assertEquals("写作业", card.value)
        assertTrue(card.line1, card.line1.startsWith("剩余 "))
    }

    @Test
    fun unlockedRowIsAbsentBecauseServerOmitsIt() {
        // lockInfo 由聚合路由按"当前守护对象"下发；未锁时 lockTime=0 → 卡仍渲染为"未锁机"。
        val cards = HomeCards.fromData(
            HomeData(lockInfo = LockInfo(lockTime = 0, lockDuration = 0)),
            texts,
            ::remainingFormatter,
        )
        assertEquals("未锁机", cards.first().value)
        assertEquals("", cards.first().line1)
    }

    @Test
    fun ownDeviceCardJoinsModelAndOs() {
        val cards = HomeCards.fromData(
            HomeData(
                phoneInfoCard = PhoneStateInfo(
                    currentBattery = 88,
                    model = "Pixel 8",
                    osVersion = "15",
                    networkType = 2,
                ),
            ),
            texts,
            ::remainingFormatter,
        )
        val card = cards.first()
        assertEquals("我的设备", card.title)
        assertEquals("88%", card.value)
        assertEquals("Pixel 8 · 15", card.line1)
        assertEquals("移动网络在线", card.line2)
    }

    @Test
    fun durationFormatting() {
        assertEquals("0分", HomeCards.formatDuration(0))
        assertEquals("23分", HomeCards.formatDuration(23 * 60))
        assertEquals("1小时0分", HomeCards.formatDuration(3600))
        assertEquals("2小时5分", HomeCards.formatDuration(2 * 3600 + 5 * 60 + 9))
    }
}
