package com.youloft.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `jh.h.g`（协议解析）的金样钉死：三种参数形态（JSON / k=v / # 分段）与切分规则。H5 侧真实
 * 调用面就压在这份解析上——改坏一个分支，页面上对应能力的调用静默变成 `-unsupport-`。
 */
class ProtocolDispatcherTest {

    private val dispatcher = ProtocolDispatcher { null }

    @Test
    fun matchesOnlyProtocolSchema() {
        assertTrue(dispatcher.matches("protocol://toast?msg=hi"))
        assertFalse(dispatcher.matches("https://example.com"))
        assertFalse(dispatcher.matches("weixin://x"))
    }

    @Test
    fun keyValueForm() {
        val envelope = dispatcher.parse("protocol://toast?msg=%E4%BD%A0%E5%A5%BD&once=true")
        assertEquals("protocol", envelope.schema)
        assertEquals("toast", envelope.method)
        assertEquals("你好", envelope.args["msg"])
        assertEquals("true", envelope.args["once"])
        assertEquals("toast?msg=%E4%BD%A0%E5%A5%BD&once=true".substringAfter("?"), envelope.argString)
    }

    @Test
    fun jsonFormDecodesToObject() {
        val url = "protocol://jumpPage?" + java.net.URLEncoder.encode("""{"scheme":"linkup://vip"}""", "utf-8")
        val envelope = dispatcher.parse(url)
        assertEquals("jumpPage", envelope.method)
        assertEquals("linkup://vip", envelope.args["scheme"])
    }

    @Test
    fun hashFragmentsFormIndexesSegments() {
        val envelope = dispatcher.parse("protocol://pager#page2#tab")
        assertEquals("pager", envelope.method)
        assertEquals("page2", envelope.args["0"])
        assertEquals("tab", envelope.args["1"])
    }

    @Test
    fun bareCommandHasEmptyArgs() {
        val envelope = dispatcher.parse("protocol://closePage")
        assertEquals("closePage", envelope.method)
        assertTrue(envelope.args.isEmpty())
    }

    @Test
    fun colonAlsoCutsTheCommand() {
        // 原版把第一个 ? : # 都当命令边界（同一排序取最小正下标）。
        val envelope = dispatcher.parse("protocol://cmd:tail")
        assertEquals("cmd", envelope.method)
    }

    @Test
    fun dispatchUrlHandsEnvelopeToHandler() {
        var seen: ProtocolDispatcher.Envelope? = null
        val dispatcher = ProtocolDispatcher { envelope ->
            seen = envelope
            "ok"
        }
        dispatcher.dispatchUrl("protocol://toast?msg=hi")
        assertEquals("toast", seen?.method)
        assertEquals("hi", seen?.args?.get("msg"))
    }
}
