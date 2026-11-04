package com.youloft.linkup.net
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * The APK Signing Block, read and written.
 *
 * Ported from the recovered `hc.a` / `hc.d` / `hc.f` (1.6.6 `kc.*`). Those are **Meituan Walle's
 * `ApkUtil`** from its `payload_reader` module — checked against upstream on 2026-11-04, where every
 * constant in `hc.a` matches: `0x7109871A` v2 pair, `0x71777777` channel pair, `0x42726577` the verity
 * padding pair (which the app never uses), the two halves of `APK Sig Block 42`, EOCD `0x06054b50` at
 * length 22 with the comment-length field at +20, min block 32, page alignment 4096.
 *
 * The block sits immediately before the ZIP Central Directory, is little endian, and is a run of
 * `[size:8][id:4][data]` pairs where `size` counts the id plus the data. The whole block is
 * `[size:8][pairs][size:8][magic:16]`, with `size = pairs + 24`.
 *
 * So the pipeline was Walle-shaped — but the payload is **not** stock Walle: the original keeps plain
 * UTF-8 JSON with a `channel` key in that pair, where Walle writes its own offset/magic blob. Both
 * shipped APKs carry `{"channel":"3305"}`, 18 bytes, measured 2026-11-04. That is why the writer here is
 * ours ([ChannelTagger]) rather than the Walle gradle plugin.
 */
object ApkSigningBlock {

    const val CHANNEL_SECTION_ID = 0x71777777
    const val V2_SIGNATURE_PAIR_ID = 0x7109871A
    const val CHANNEL_KEY = "channel"

    private const val EOCD_SIGNATURE = 0x06054b50
    private const val EOCD_LENGTH = 22
    private const val EOCD_CENTRAL_DIRECTORY_OFFSET_FIELD = 16
    private const val MAX_EOCD_SEARCH = 0xFFFF
    private const val MIN_BLOCK_LENGTH = 32

    private const val PAIR_ID_LENGTH = 4
    private const val SIZE_FIELD_LENGTH = 8
    private const val BLOCK_TRAILER_LENGTH = 24
    private const val MAGIC_OFFSET_IN_TRAILER = 8
    private const val MIN_PAIR_SIZE = 4L
    private const val MAX_PAIR_SIZE = 2_000_000_000L

    private val MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

    class FormatError(message: String) : IOException(message)

    /** Every pair in the block, file order. `null` when this APK carries no signing block. */
    fun pairs(apk: File): LinkedHashMap<Int, ByteArray>? = runCatching {
        RandomAccessFile(apk, "r").use { file ->
            pairsOf(file, file.blockStart(file.centralDirectoryOffset()) ?: return@use null, file.centralDirectoryOffset())
        }
    }.getOrNull()

    private fun pairsOf(file: RandomAccessFile, blockStart: Long, centralDirectory: Long): LinkedHashMap<Int, ByteArray>? =
        if (blockStart == centralDirectory) null else file.readPairs(blockStart, centralDirectory)

    /** The ZIP file comment, if any — reading it back is the cheapest proof the EOCD was relocated right. */
    fun zipComment(apk: File): ByteArray? = runCatching {
        RandomAccessFile(apk, "r").use { file ->
            val commentLength = file.zipCommentLength()
            if (commentLength == 0) return@use ByteArray(0)
            file.readAt(file.length() - commentLength, commentLength)
        }
    }.getOrNull()

    /**
     * The recovered `hc.d.b` read this with `org.json`; Gson is used here because that is what the rest
     * of the rebuild parses with (and `org.json` is a stub in JVM unit tests). Same flattening rule:
     * every value is read back as a string, so a non-string value reads as null → the whole section is
     * treated as unusable, exactly as a `JSONException` did in the original.
     */
    fun jsonSection(apk: File, id: Int = CHANNEL_SECTION_ID): Map<String, String>? {
        val bytes = pairs(apk)?.get(id) ?: return null
        return runCatching {
            val element = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            element.entrySet().associate { (key, value) -> key to value.asJsonPrimitive.asString }
        }.getOrNull()
    }

    /**
     * Add or replace one pair, streaming [source] into [target]. Kept as two files rather than an
     * in-place rewrite: the source stays open while the target is written (and a file cannot be
     * replaced underneath an open handle on Windows).
     *
     * No re-signing is needed afterwards: **only the platform's own pairs feed the v2/v3 signed data**, so
     * an extra pair with an unassigned id sits outside the digest. Measured, not assumed — stamping the
     * built `app-debug.apk` leaves `apksigner verify` reporting v2 = `true` with the same signer
     * certificate, and `unzip -t` reports no CRC errors.
     */
    fun stamp(source: File, target: File, id: Int, payload: ByteArray) {
        val out = java.io.BufferedOutputStream(target.outputStream())
        RandomAccessFile(source, "r").use { file ->
            val centralDirectory = file.centralDirectoryOffset()
            val blockStart = file.blockStart(centralDirectory) ?: centralDirectory
            val pairs = LinkedHashMap(pairsOf(file, blockStart, centralDirectory) ?: linkedMapOf()).apply {
                put(id, payload)
            }
            val block = buildBlock(pairs)

            out.write(file.slice(0L, blockStart))
            out.write(block)
            val trailer = file.slice(centralDirectory, file.length() - centralDirectory)
            patchCentralDirectoryOffset(trailer, blockStart.toInt() + block.size)
            out.write(trailer)
        }
        out.flush()
        out.close()
    }

    private fun patchCentralDirectoryOffset(trailer: ByteArray, newCentralDirectory: Int) {
        val view = ByteBuffer.wrap(trailer).order(ByteOrder.LITTLE_ENDIAN)
        var search = 0
        while (search <= MAX_EOCD_SEARCH) {
            val at = trailer.size - EOCD_LENGTH - search
            if (at < 0) break
            if (view.getInt(at) == EOCD_SIGNATURE && view.getShort(at + 20).toInt() == search) {
                view.putInt(at + EOCD_CENTRAL_DIRECTORY_OFFSET_FIELD, newCentralDirectory)
                return
            }
            search++
        }
        throw FormatError("ZIP End of Central Directory record not found while rewriting")
    }

    private fun buildBlock(pairs: Map<Int, ByteArray>): ByteArray {
        val pairsLength = pairs.values.sumOf { SIZE_FIELD_LENGTH + PAIR_ID_LENGTH + it.size }
        val block = ByteBuffer.allocate(pairsLength + SIZE_FIELD_LENGTH + BLOCK_TRAILER_LENGTH)
            .order(ByteOrder.LITTLE_ENDIAN)
        val signingBlockSize = pairsLength + BLOCK_TRAILER_LENGTH
        block.putLong(signingBlockSize.toLong())
        pairs.forEach { (id, data) ->
            block.putLong((PAIR_ID_LENGTH + data.size).toLong())
            block.putInt(id)
            block.put(data)
        }
        block.putLong(signingBlockSize.toLong())
        block.put(MAGIC)
        return block.array()
    }

    private fun RandomAccessFile.centralDirectoryOffset(): Long {
        val commentLength = zipCommentLength()
        val endOfCentralDirectory = length() - commentLength - EOCD_LENGTH
        if (endOfCentralDirectory < 0) throw FormatError("file is smaller than an EOCD record")
        return readLittleInt(endOfCentralDirectory + EOCD_CENTRAL_DIRECTORY_OFFSET_FIELD).toLong() and 0xFFFFFFFFL
    }

    /** Scans back for the EOCD, the same way apksig does: signature plus a matching comment length. */
    private fun RandomAccessFile.zipCommentLength(): Int {
        val size = length()
        if (size < EOCD_LENGTH) throw FormatError("APK too small for a ZIP End of Central Directory record")
        val limit = minOf(size - EOCD_LENGTH, MAX_EOCD_SEARCH.toLong())
        var candidate = 0L
        while (candidate <= limit) {
            val at = size - EOCD_LENGTH - candidate
            if (readLittleInt(at) == EOCD_SIGNATURE && readLittleShort(at + 20).toLong() == candidate) {
                return candidate.toInt() and 0xFFFF
            }
            candidate++
        }
        throw FormatError("ZIP End of Central Directory record not found")
    }

    /** Where the signing block starts, or `null` when the bytes before the Central Directory aren't one. */
    private fun RandomAccessFile.blockStart(centralDirectory: Long): Long? {
        if (centralDirectory < MIN_BLOCK_LENGTH) return null
        val trailer = ByteBuffer.wrap(readAt(centralDirectory - BLOCK_TRAILER_LENGTH, BLOCK_TRAILER_LENGTH))
            .order(ByteOrder.LITTLE_ENDIAN)
        val signingBlockSize = trailer.getLong(0)
        val magic = ByteArray(MAGIC.size)
        trailer.position(MAGIC_OFFSET_IN_TRAILER)
        trailer.get(magic)
        if (!magic.contentEquals(MAGIC)) return null
        if (signingBlockSize < MIN_BLOCK_LENGTH - SIZE_FIELD_LENGTH || signingBlockSize > Int.MAX_VALUE - 64L) return null
        val start = centralDirectory - (signingBlockSize + SIZE_FIELD_LENGTH)
        if (start < 0) return null
        if (readLittleLong(start) != signingBlockSize) return null
        return start
    }

    private fun RandomAccessFile.readPairs(blockStart: Long, centralDirectory: Long): LinkedHashMap<Int, ByteArray> {
        val length = (centralDirectory - blockStart).toInt()
        val block = ByteBuffer.wrap(readAt(blockStart, length)).order(ByteOrder.LITTLE_ENDIAN)
        val pairs = LinkedHashMap<Int, ByteArray>()
        var cursor = SIZE_FIELD_LENGTH
        val end = length - BLOCK_TRAILER_LENGTH
        var entry = 0
        while (cursor < end) {
            if (end - cursor < SIZE_FIELD_LENGTH) throw FormatError("insufficient data for pair #$entry size")
            val pairSize = block.getLong(cursor)
            cursor += SIZE_FIELD_LENGTH
            entry++
            if (pairSize < MIN_PAIR_SIZE || pairSize > MAX_PAIR_SIZE || pairSize > end - cursor) {
                throw FormatError("pair #$entry size out of range: $pairSize")
            }
            val id = block.getInt(cursor)
            val data = ByteArray((pairSize - PAIR_ID_LENGTH).toInt())
            block.position(cursor + PAIR_ID_LENGTH)
            block.get(data)
            pairs[id] = data
            cursor += pairSize.toInt()
        }
        return pairs
    }

    private fun RandomAccessFile.readAt(offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        seek(offset)
        readFully(bytes)
        return bytes
    }

    private fun RandomAccessFile.readLittleInt(offset: Long): Int =
        ByteBuffer.wrap(readAt(offset, 4)).order(ByteOrder.LITTLE_ENDIAN).int

    private fun RandomAccessFile.readLittleShort(offset: Long): Short =
        ByteBuffer.wrap(readAt(offset, 2)).order(ByteOrder.LITTLE_ENDIAN).short

    private fun RandomAccessFile.readLittleLong(offset: Long): Long =
        ByteBuffer.wrap(readAt(offset, 8)).order(ByteOrder.LITTLE_ENDIAN).long

    private fun RandomAccessFile.slice(from: Long, length: Long): ByteArray = readAt(from, length.toInt())
}
