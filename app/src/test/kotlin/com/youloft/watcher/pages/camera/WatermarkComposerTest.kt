package com.youloft.watcher.pages.camera

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/** 水印卡文案格式（时间/日期/星期）与原版同源（HH:mm、yyyy-MM-dd、周X）。 */
class WatermarkComposerTest {

    @Test
    fun formats() {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 7, 9, 5, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val time = calendar.timeInMillis
        assertEquals("09:05", WatermarkComposer.formatTime(time))
        assertEquals("2026-11-07", WatermarkComposer.formatDate(time))
        val names = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        assertEquals(names[calendar.get(Calendar.DAY_OF_WEEK) - 1], WatermarkComposer.formatWeek(time))
    }
}
