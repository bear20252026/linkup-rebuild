package com.youloft.watcher.bean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `bean/CmdScreenShot` 的线上形状：字段名即 JSON 键（R8 抹掉 SerializedName 的既定事实）。 */
class CmdScreenShotTest {

    @Test
    fun roundTripKeepsStepSemantics() {
        val payload = CmdScreenShot(
            userid = 10000001,
            step = 3,
            callBack = 1,
            accessibilityAvailable = true,
            screenShotSuccess = true,
            screenShotImage = "http://minio/shot.png",
        )
        val parsed = CmdScreenShot.fromJson(payload.toJson())!!
        assertEquals(10000001, parsed.userid)
        assertEquals(3, parsed.step)
        assertEquals(1, parsed.callBack)
        assertEquals(true, parsed.accessibilityAvailable)
        assertEquals(true, parsed.screenShotSuccess)
        assertEquals("http://minio/shot.png", parsed.screenShotImage)
    }

    @Test
    fun nullImageStaysNull() {
        val parsed = CmdScreenShot.fromJson(CmdScreenShot(userid = 1, step = 1, callBack = 1).toJson())!!
        assertNull(parsed.screenShotImage)
    }

    @Test
    fun garbageJsonAnswersNull() {
        assertNull(CmdScreenShot.fromJson("not-json"))
    }
}
