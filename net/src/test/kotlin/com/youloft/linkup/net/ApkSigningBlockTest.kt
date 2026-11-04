package com.youloft.linkup.net

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

/**
 * The channel section is a byte format, so it is tested as one: an empty-zip file is built here,
 * [ApkSigningBlock.stamp] writes a section into it, and the reader has to find it again. Testing only
 * the reader against hand-typed bytes would leave the writer unproven — and the writer is the half
 * that has to agree with what the shipped app reads.
 */
class ApkSigningBlockTest {

    private val temp = mutableListOf<File>()

    @After
    fun cleanUp() {
        temp.forEach { it.delete() }
        temp.clear()
    }

    /** The smallest legal ZIP: an EOCD record and nothing else, i.e. no signing block. */
    private fun emptyZip(comment: String = ""): File {
        val file = File.createTempFile("apk-block", ".apk").also { temp += it }
        val bytes = ByteBuffer.allocate(22 + comment.length).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(EOCD_SIGNATURE)
            putShort(0); putShort(0); putShort(0); putShort(0)
            putInt(0) // central directory length
            putInt(0) // central directory offset: nothing in front of it
            putShort(comment.length.toShort())
            put(comment.toByteArray(Charsets.UTF_8))
        }
        file.writeBytes(bytes.array())
        return file
    }

    private fun stamped(source: File, id: Int = ApkSigningBlock.CHANNEL_SECTION_ID, payload: ByteArray): File {
        val target = File.createTempFile("stamped", ".apk").also { temp += it }
        ApkSigningBlock.stamp(source, target, id, payload)
        return target
    }

    private fun channelJson(channel: String, extra: Pair<String, String>? = null): ByteArray {
        val tail = extra?.let { ",\"${it.first}\":\"${it.second}\"" } ?: ""
        return """{"channel":"$channel"$tail}""".toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `an apk with no signing block reads as no pairs`() {
        val apk = emptyZip()

        assertNull(ApkSigningBlock.pairs(apk))
        assertNull(ApkSigningBlock.jsonSection(apk))
    }

    @Test
    fun `a stamped section is found again and the zip still parses`() {
        val apk = stamped(emptyZip(), payload = channelJson("3301"))

        val pairs = assertNotNull(ApkSigningBlock.pairs(apk))
        assertEquals(1, pairs.size)
        assertEquals("""{"channel":"3301"}""", String(pairs.getValue(ApkSigningBlock.CHANNEL_SECTION_ID), Charsets.UTF_8))
        assertEquals(mapOf("channel" to "3301"), ApkSigningBlock.jsonSection(apk))
    }

    @Test
    fun `the central directory offset moves with the block and the comment survives`() {
        val apk = stamped(emptyZip(comment = "keepme"), payload = channelJson("3801"))

        // Reading the comment back is what proves the EOCD was relocated and its offset rewritten.
        assertEquals("keepme", String(assertNotNull(ApkSigningBlock.zipComment(apk)), Charsets.UTF_8))
    }

    @Test
    fun `stamping twice keeps both pairs and the newest channel wins`() {
        val first = stamped(emptyZip(), payload = channelJson("3301"))
        val withSignature = stamped(first, ApkSigningBlock.V2_SIGNATURE_PAIR_ID, "signature-bytes".toByteArray())
        val restamped = stamped(withSignature, payload = channelJson("0"))

        val pairs = assertNotNull(ApkSigningBlock.pairs(restamped))
        assertEquals(2, pairs.size)
        assertEquals("""{"channel":"0"}""", String(pairs.getValue(ApkSigningBlock.CHANNEL_SECTION_ID), Charsets.UTF_8))
        assertEquals("signature-bytes", String(pairs.getValue(ApkSigningBlock.V2_SIGNATURE_PAIR_ID), Charsets.UTF_8))
    }

    @Test
    fun `the section keeps the channel key separate from everything else`() {
        val apk = stamped(emptyZip(), payload = channelJson("3302", "build" to "internal"))

        val section = assertNotNull(ApkSigningBlock.jsonSection(apk))

        assertEquals("3302", section.getValue(ApkSigningBlock.CHANNEL_KEY))
        assertEquals(mapOf("build" to "internal"), section.minus(ApkSigningBlock.CHANNEL_KEY))
    }

    @Test
    fun `a missing key and a missing section both read as no channel`() {
        val wrongKey = stamped(emptyZip(), payload = """{"other":"x"}""".toByteArray())

        assertNull(assertNotNull(ApkSigningBlock.jsonSection(wrongKey))[ApkSigningBlock.CHANNEL_KEY])
        assertNull(ApkSigningBlock.jsonSection(emptyZip()))
    }

    @Test
    fun `a section full of junk is ignored rather than thrown`() {
        val apk = stamped(emptyZip(), payload = "not json at all".toByteArray())

        assertNull(ApkSigningBlock.jsonSection(apk))
        assertTrue(assertNotNull(ApkSigningBlock.pairs(apk)).isNotEmpty())
    }

    @Test
    fun `bytes that are not a zip at all read as no block`() {
        val junk = File.createTempFile("junk", ".apk").also { temp += it; it.writeBytes(ByteArray(4096) { 7 }) }

        assertNull(ApkSigningBlock.pairs(junk))
        assertNull(ApkSigningBlock.zipComment(junk))
    }

    @Test
    fun `the stamped block is framed the way the platform reads it`() {
        val apk = stamped(emptyZip(), payload = channelJson("3305"))
        val bytes = apk.readBytes()

        val footer = ByteBuffer.wrap(bytes, bytes.size - 22 - 24, 24).order(ByteOrder.LITTLE_ENDIAN)
        val signingBlockSize = footer.getLong(0)
        val header = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong(0)

        assertEquals("APK Sig Block 42", String(bytes, bytes.size - 22 - 16, 16, Charsets.US_ASCII))
        assertEquals(signingBlockSize, header, "header and footer must agree, or the platform rejects the block")
        // The block sits directly in front of the Central Directory, and its size field counts itself.
        assertEquals(bytes.size - 22L, header + 8L, "Central Directory must start right after the block")
        assertEquals(
            """{"channel":"3305"}""",
            String(assertNotNull(ApkSigningBlock.pairs(apk)).getValue(ApkSigningBlock.CHANNEL_SECTION_ID), Charsets.UTF_8),
            "the shipped APKs carry exactly this 18-byte payload",
        )
    }

    private companion object {
        const val EOCD_SIGNATURE = 0x06054b50
    }
}
