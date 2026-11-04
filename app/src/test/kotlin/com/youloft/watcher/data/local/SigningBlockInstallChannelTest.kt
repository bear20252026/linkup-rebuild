package com.youloft.watcher.data.local

import com.youloft.linkup.net.ApkSigningBlock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The gate rules of the friend screens all hang off this one value, so both branches are pinned: a
 * build that carries the section reports its channel, and a build that doesn't reports the fallback
 * instead of throwing at app start.
 */
class SigningBlockInstallChannelTest {

    private fun emptyZip(): File {
        val file = File.createTempFile("channel", ".apk")
        val bytes = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x06054b50)
            putShort(0); putShort(0); putShort(0); putShort(0)
            putInt(0); putInt(0); putShort(0)
        }
        file.writeBytes(bytes.array())
        return file
    }

    @Test
    fun `a build with the section reports its channel and keeps the rest as extras`() {
        val plain = emptyZip()
        val stamped = File.createTempFile("stamped", ".apk")
        ApkSigningBlock.stamp(
            plain,
            stamped,
            ApkSigningBlock.CHANNEL_SECTION_ID,
            """{"channel":"3301","build":"internal"}""".toByteArray(),
        )

        val channel = SigningBlockInstallChannel(stamped)

        assertEquals("3301", channel.id())
        assertEquals(mapOf("build" to "internal"), channel.extras())
        plain.delete(); stamped.delete()
    }

    @Test
    fun `a build without the section answers the fallback rather than failing`() {
        val apk = File.createTempFile("unsigned", ".apk").apply { writeBytes(ByteArray(0)) }

        assertEquals("0", SigningBlockInstallChannel(emptyZip()).id())
        assertEquals("0", SigningBlockInstallChannel(apk).id())
        assertEquals("0", SigningBlockInstallChannel(null).id())
        assertEquals("official", SigningBlockInstallChannel(emptyZip(), fallback = "official").id())
        apk.delete()
    }

    private fun gate(apk: File, fallback: String = "0") = PermissionChannelGate(
        SigningBlockInstallChannel(apk, fallback),
        object : RemoteConfig {
            override fun stringList(key: String): List<String> = emptyList()
        },
    )

    @Test
    fun `the channel drives the shipping permission gate`() {
        // 1.6.6 asks for the consent sheet on exactly one channel, and our own build is not it.
        assertTrue(gate(stampedWith("3301")).asksAfterAddFriend())
        assertFalse(gate(stampedWith("3302")).asksAfterAddFriend())
        assertFalse(gate(emptyZip()).asksAfterAddFriend())
    }

    @Test
    fun `the channel drives the add-friend hint rules`() {
        assertEquals(AddFriendTips.BothInstallOrVip, AddFriendTipsPolicy(SigningBlockInstallChannel(stampedWith("3301"))).tips())
        assertEquals(AddFriendTips.LocationMutualConsent, AddFriendTipsPolicy(SigningBlockInstallChannel(stampedWith("3801"))).tips())
        assertNull(AddFriendTipsPolicy(SigningBlockInstallChannel(emptyZip())).tips())
    }

    private fun stampedWith(channel: String): File {
        val target = File.createTempFile("stamped", ".apk")
        ApkSigningBlock.stamp(emptyZip(), target, ApkSigningBlock.CHANNEL_SECTION_ID, """{"channel":"$channel"}""".toByteArray())
        return target
    }
}
