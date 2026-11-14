package com.youloft.watcher.data

import android.content.Intent
import com.youloft.linkup.api.dto.SensType
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** 敏感行为采集的类型映射（[sensTypeOf]）：三类系统广播进台账，其余 action 不产生行。纯 JVM。 */
class SensitiveEventReceiverTest {

    @Test
    fun `the three package broadcasts map onto the rebuild's type table`() {
        assertEquals(SensType.APP_INSTALL, sensTypeOf(Intent.ACTION_PACKAGE_ADDED))
        assertEquals(SensType.APP_UNINSTALL, sensTypeOf(Intent.ACTION_PACKAGE_REMOVED))
        assertEquals(SensType.APP_UPDATE, sensTypeOf(Intent.ACTION_PACKAGE_REPLACED))
    }

    @Test
    fun `every other action produces no ledger row`() {
        assertNull(sensTypeOf(Intent.ACTION_BOOT_COMPLETED), "原版类型表不可恢复,未定义的口径不编造")
        assertNull(sensTypeOf(""))
        assertNull(sensTypeOf("com.other.SOMETHING"))
    }
}
