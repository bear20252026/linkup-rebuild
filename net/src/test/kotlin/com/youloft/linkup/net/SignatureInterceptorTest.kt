package com.youloft.linkup.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class SignatureInterceptorTest {
    private val context = StaticDeviceContext(
        deviceId = "dev-1",
        distinctId = "distinct-1",
        shushuDeviceId = "shushu-1",
        token = "jwt-abc",
    )

    private val expectedParams = listOf(
        "tz", "lang", "userId", "client", "deviceId", "channel",
        "av", "t", "distinctId", "shushuDeviceId", "mainUser",
    )

    private fun signedClient() =
        OkHttpClient.Builder().addInterceptor(SignatureInterceptor(context)).build()

    @Test
    fun `signature decrypts back to exactly path plus query`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":200,"msg":"success"}"""))
            server.start()

            signedClient().newCall(Request.Builder().url(server.url("/api/client/config")).build())
                .execute().use { it.body?.string() }

            val recorded = server.takeRequest()
            val url = recorded.requestUrl!!
            assertEquals(url.encodedPath + "?" + url.encodedQuery, AesCbc.decryptHex(recorded.headers["Signature"]!!))
            assertEquals("jwt-abc", recorded.headers["Authorization"])
            assertEquals(expectedParams, url.encodedQuery!!.split("&").map { it.substringBefore("=") })
            assertEquals("android", url.queryParameter("client"))
            assertEquals("dev-1", url.queryParameter("deviceId"))
        }
    }

    @Test
    fun `post signature appends lowercase md5 of the body`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":200,"msg":"success"}"""))
            server.start()
            val text = """{"push":"getui"}"""
            val body = text.toRequestBody("application/json;charset=UTF-8".toMediaType())

            signedClient().newCall(
                Request.Builder().url(server.url("/api/user/update/push/token")).post(body).build(),
            ).execute().use { it.body?.string() }

            val recorded = server.takeRequest()
            val url = recorded.requestUrl!!
            val expected = url.encodedPath + "?" + url.encodedQuery + md5Hex(text)
            assertEquals(expected, AesCbc.decryptHex(recorded.headers["Signature"]!!))
        }
    }

    @Test
    fun `login and sms paths carry query params but no auth headers`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":200,"msg":"success"}"""))
            server.start()

            signedClient().newCall(Request.Builder().url(server.url("/api/sms/send")).build())
                .execute().use { it.body?.string() }

            val recorded = server.takeRequest()
            assertNull(recorded.headers["Signature"])
            assertNull(recorded.headers["Authorization"])
            assertEquals(expectedParams, recorded.requestUrl!!.encodedQuery!!.split("&").map { it.substringBefore("=") })
        }
    }

    @Test
    fun `requests to the excluded host are passed through untouched`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            server.start()

            OkHttpClient.Builder()
                .addInterceptor(SignatureInterceptor(context, unauthenticatedHost = server.hostName))
                .build()
                .newCall(Request.Builder().url(server.url("/sns/oauth2/access_token")).build())
                .execute().use { it.body?.string() }

            val recorded = server.takeRequest()
            assertNull(recorded.requestUrl!!.query)
            assertNull(recorded.headers["Signature"])
        }
    }

    @Test
    fun `aes round trip and md5 match the recovered algorithm`() {
        val signatureInput = "/api/client/config?tz=480&lang=zh&userId=0&client=android"
        val hex = AesCbc.encryptHex(signatureInput)
        assertEquals(signatureInput, AesCbc.decryptHex(hex))
        assertEquals(32, md5Hex("abc").length)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5Hex("abc"))
    }
}
