package com.youloft.watcher.pages.chat

import kotlin.test.assertEquals
import org.junit.Test

/** 语音气泡文案（[formatVoice]）：一分内秒数、超一分分秒、播放中 ▶ 提示——纯函数 JVM 钉住。 */
class ChatVoiceFormatTest {

    @Test
    fun `sub-minute duration reads as seconds with the mic glyph`() {
        assertEquals("🎤 5″", formatVoice(5L, playing = false))
        assertEquals("🎤 1″", formatVoice(1L, playing = false), "the recorder floors at one second")
    }

    @Test
    fun `minute-and-over durations read as minutes and seconds`() {
        assertEquals("🎤 1:00", formatVoice(60L, playing = false))
        assertEquals("🎤 1:05", formatVoice(65L, playing = false))
    }

    @Test
    fun `the playing row swaps the glyph for the play head`() {
        assertEquals("▶ 5″", formatVoice(5L, playing = true))
        assertEquals("▶ 1:05", formatVoice(65L, playing = true))
    }
}
