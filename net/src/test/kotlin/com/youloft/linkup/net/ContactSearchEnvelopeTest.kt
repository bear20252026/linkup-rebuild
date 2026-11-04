package com.youloft.linkup.net

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactSearchEnvelopeTest {

    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** The exact footer the recovered client strips is four dashes, so that is what the server must send. */
    private fun pem(body: String) = "-----BEGIN PUBLIC KEY-----\n" + body + "\n----END PUBLIC KEY-----\n"

    private fun serverPem(): String =
        pem(Base64.getEncoder().withoutPadding().encodeToString((pair.public as RSAPublicKey).encoded).chunked(64).joinToString("\n"))

    @Test
    fun `envelope decrypts back to the phone list the caller passed in`() {
        val key = ByteArray(16) { (it + 1).toByte() }
        val envelope = ContactSearchEnvelope(randomKey = { key }, nowSeconds = { 1_700_000_000L })

        val payload = assertNotNull(envelope.build(listOf("13800138000", "12345678"), keyId = 42L, serverPublicKeyPem = serverPem()))

        val unwrapped = cipher(Cipher.DECRYPT_MODE, pair.private).doFinal(decode(payload.keyEncrypted))
        assertEquals(16, unwrapped.size)
        assertTrue(unwrapped.contentEquals(key))

        val json = String(
            Cipher.getInstance("AES/CBC/PKCS5Padding").let {
                it.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
                it.doFinal(decode(payload.encrypted))
            },
            Charsets.UTF_8,
        )
        assertEquals(
            """{"keyID":42,"ciphertext":"eyJwaG9uZUxpc3QiOlsiMTM4MDAxMzgwMDAiLCIxMjM0NTY3OCJdfQ==","timestamp":1700000000}""",
            json,
        )
        assertEquals(
            """{"phoneList":["13800138000","12345678"]}""",
            String(decode(json.substringAfter("\"ciphertext\":\"").substringBefore("\"")), Charsets.UTF_8),
        )
    }

    @Test
    fun `a standard five-dash footer is not what the recovered client strips`() {
        val standard = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getEncoder().withoutPadding().encodeToString(pair.public.encoded) +
            "\n-----END PUBLIC KEY-----\n"

        // The leftover dash makes the base64 body undecodable, so the caller sees "no key" and takes
        // its 10086 branch. Pinned because it is a real integration risk, not a style choice.
        assertNull(ContactSearchEnvelope().build(listOf("13800138000"), 1L, standard))
    }

    @Test
    fun `absent or empty server key yields no envelope`() {
        val envelope = ContactSearchEnvelope()
        assertNull(envelope.build(listOf("13800138000"), 1L, null))
        assertNull(envelope.build(listOf("13800138000"), 1L, ""))
        assertNull(envelope.build(listOf("13800138000"), 1L, "-----BEGIN PUBLIC KEY-----\n\n----END PUBLIC KEY-----\n"))
    }

    private fun cipher(mode: Int, key: java.security.Key): Cipher =
        Cipher.getInstance(ContactSearchEnvelope.RSA_TRANSFORMATION).apply { init(mode, key) }

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}
