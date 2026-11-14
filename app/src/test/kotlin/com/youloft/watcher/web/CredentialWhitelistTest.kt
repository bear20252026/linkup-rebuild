package com.youloft.watcher.web

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * 凭据白名单（审计 P1-4）：容器内的导航只有自家域拿到 userid/token——白名单外的 URL（含
 * h5Activity/jumpPage 打开的任意页面）照常导航但拿不到凭据，JWT 外泄链在追加环节关死。
 * 纯函数 JVM 钉住：host 匹配（精确/子域/大小写/解析失败）、追加形态（? 与 & 的分隔）。
 */
class CredentialWhitelistTest {

    @Test
    fun `trusted hosts get credentials with the right separator`() {
        assertEquals(
            "https://web-link.tttick.com/business/x?userid=7&token=tk&av=1.6.6&client=android",
            appendCredentials("https://web-link.tttick.com/business/x", 7L, "tk"),
        )
        assertEquals(
            "https://gateway.cloudbear.cloud/api/index?fullscreen=true&userid=7&token=tk&av=1.6.6&client=android",
            appendCredentials("https://gateway.cloudbear.cloud/api/index?fullscreen=true", 7L, "tk"),
        )
    }

    @Test
    fun `subdomains and casing count as the trusted domain`() {
        assertTrue(hostNeedsCredentials("https://a.web-link.tttick.com/page"))
        assertTrue(hostNeedsCredentials("https://WEB-LINK.TTTICK.COM/page"))
        assertTrue(hostNeedsCredentials("https://gateway.cloudbear.cloud/x"))
    }

    @Test
    fun `foreign hosts never get credentials`() {
        assertFalse(hostNeedsCredentials("https://attacker.example/x"), "任意打开的外域拿不到 JWT")
        assertFalse(hostNeedsCredentials("http://qrcode.tttick.com.evil.example/"), "后缀伪装不是子域")
        assertFalse(hostNeedsCredentials("https://tttick.com.evil.example/"))
        assertFalse(hostNeedsCredentials("not a url"))
        assertFalse(hostNeedsCredentials(""), "空串如实答 false")
    }
}
