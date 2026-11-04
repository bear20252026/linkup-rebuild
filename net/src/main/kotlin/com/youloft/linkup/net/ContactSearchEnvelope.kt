package com.youloft.linkup.net

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Recovered from 1.5.6 `AddFriendViewModel$searchFriendInfo` (+ 1.6.6 `AddFriendViewModel$t`): the
 * contact list is never sent in the clear.
 *
 *  1. `{"phoneList":[...]}` is base64'd (no wrap) and placed in `ciphertext` — that inner field is
 *     only encoded, not encrypted; the encryption happens one layer out.
 *  2. A fresh 16-byte key is generated and used as *both* the AES key and the AES IV.
 *  3. `{keyID, ciphertext, timestamp}` is AES/CBC/PKCS7-encrypted with that key.
 *  4. The key itself is RSA-OAEP-SHA-256 encrypted with the server's public key.
 *
 * The random source and the clock are parameters because the whole envelope is otherwise
 * non-deterministic and this is the one protocol branch that has to be asserted byte-for-byte.
 */
class ContactSearchEnvelope(
    private val randomKey: () -> ByteArray = { ByteArray(KEY_SIZE).also { java.security.SecureRandom().nextBytes(it) } },
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    // Gson escapes `=` as \u003d by default, which would corrupt the inner base64; the original built
    // these two objects with org.json, which does not.
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
) {

    /** The two fields `/api/relation/match_by_phone` takes as its body. */
    data class Payload(val encrypted: String, val keyEncrypted: String)

    /** Returns null when the server key is missing or unusable, which is what the caller branches on. */
    fun build(phones: List<String>, keyId: Long, serverPublicKeyPem: String?): Payload? {
        val publicKey = parsePublicKey(serverPublicKeyPem) ?: return null
        val inner = gson.toJson(linkedMapOf("phoneList" to phones))
        val payload = gson.toJson(
            linkedMapOf(
                "keyID" to keyId,
                "ciphertext" to encodeNoWrap(inner.toByteArray(Charsets.UTF_8)),
                "timestamp" to nowSeconds(),
            ),
        )
        val key = randomKey()
        return Payload(
            encrypted = encodeNoWrap(aes(payload.toByteArray(Charsets.UTF_8), key)),
            keyEncrypted = encodeNoWrap(rsaOaep(key, publicKey)),
        )
    }

    /**
     * The recovered client strips exactly these two markers — note the second one has four leading
     * dashes, not the five a standard PEM footer carries. Kept as-is: changing it changes what the
     * server can decrypt.
     */
    private fun parsePublicKey(pem: String?): PublicKey? {
        if (pem.isNullOrBlank()) return null
        val body = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("----END PUBLIC KEY-----", "")
            .replace(Regex("\\s+"), "")
        if (body.isEmpty()) return null
        return runCatching {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decodeNoWrap(body)))
        }.getOrNull()
    }

    private fun aes(plain: ByteArray, key: ByteArray): ByteArray =
        // The original asks for "AES/CBC/PKCS7PADDING"; for a 16-byte block cipher PKCS5Padding is
        // the same bytes, and it is the name the JVM provider accepts.
        Cipher.getInstance("AES/CBC/PKCS5Padding").let {
            it.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
            it.doFinal(plain)
        }

    private fun rsaOaep(key: ByteArray, publicKey: PublicKey): ByteArray =
        Cipher.getInstance(RSA_TRANSFORMATION).let {
            it.init(Cipher.ENCRYPT_MODE, publicKey)
            it.doFinal(key)
        }

    private fun encodeNoWrap(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decodeNoWrap(text: String): ByteArray = Base64.getDecoder().decode(text)

    companion object {
        const val KEY_SIZE = 16
        const val RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    }
}
