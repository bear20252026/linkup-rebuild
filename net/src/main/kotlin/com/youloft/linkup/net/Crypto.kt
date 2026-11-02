package com.youloft.linkup.net

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Recovered from com.youloft.watcher.utils.a (AESCBCUtil.kt) in LinkUp 1.6.6.
 * KEY and IV are the same literal, and both are used as raw UTF-8 bytes rather than hex.
 */
object AesCbc {
    const val KEY: String = "6R4aXTSK3OghHIgA"

    // The original requests "AES/CBC/PKCS7PADDING"; for a 16-byte block cipher that padding is
    // byte-for-byte identical to PKCS5Padding, which is the name SunJCE/Conscrypt actually accept.
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"

    fun encryptHex(plain: String): String {
        val keyBytes = KEY.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(keyBytes))
        }
        return cipher.doFinal(plain.toByteArray(Charsets.UTF_8)).toLowerHex()
    }

    fun decryptHex(hex: String): String {
        val keyBytes = KEY.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(keyBytes))
        }
        return String(cipher.doFinal(hex.trim().hexToBytes()), Charsets.UTF_8)
    }
}

fun ByteArray.toLowerHex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (b in this) {
        out.append(digits[(b.toInt() ushr 4) and 0x0F]).append(digits[b.toInt() and 0x0F])
    }
    return out.toString()
}

fun String.hexToBytes(): ByteArray {
    val s = trim().uppercase()
    require(s.length % 2 == 0) { "hex string must have even length: $s" }
    return ByteArray(s.length / 2) { i ->
        (Character.digit(s[i * 2], 16) shl 4 or Character.digit(s[i * 2 + 1], 16)).toByte()
    }
}

fun md5(text: String): ByteArray =
    MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))

fun md5Hex(text: String): String = md5(text).toLowerHex()
