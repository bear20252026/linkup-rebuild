package com.youloft.linkup.net

import java.io.IOException
import java.util.Locale
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer

/**
 * Line-for-line reproduction of the 1.6.6 request signer (gh/d.java).
 *
 * Wire contract, all confirmed against linkup-test.51wnl-cq.com:
 *  - signature input is `encodedPath + "?" + encodedQuery` of the *rewritten* URL
 *  - non-GET requests with a body append `md5Hex(bodyText).lowercase()`
 *  - Signature = hex(AES-128-CBC-PKCS7(signatureInput)) with KEY == IV; no MD5 wrapper (that
 *    wrapper only exists in the 1.5.x yftracker SDK, which still talks to dmp.51wnl-cq.com)
 *  - requests whose path contains a `login` or `sms` segment get the query params but no headers
 */
class SignatureInterceptor(
    private val context: DeviceContext,
    private val unauthenticatedHost: String = WECHAT_HOST,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.url.host.endsWith(unauthenticatedHost)) {
            return chain.proceed(original)
        }

        val withCommonParams = original.url.newBuilder()
            .scheme(original.url.scheme)
            .addEncodedQueryParameter("tz", context.timeZoneOffsetMinutes.toString())
            .addEncodedQueryParameter("lang", context.language)
            .addEncodedQueryParameter("userId", context.userId.toString())
            .addEncodedQueryParameter("client", CLIENT)
            .addEncodedQueryParameter("deviceId", context.deviceId)
            .addEncodedQueryParameter("channel", context.channel)
            .addEncodedQueryParameter("av", context.appVersion)
            .addEncodedQueryParameter("t", System.currentTimeMillis().toString())
            .addEncodedQueryParameter("distinctId", context.distinctId)
            .addEncodedQueryParameter("shushuDeviceId", context.shushuDeviceId)
            .addEncodedQueryParameter("mainUser", context.mainUser.toString())
            .build()

        val request = original.newBuilder()
            .method(original.method, original.body)
            .url(withCommonParams)
            .build()

        var signatureInput = request.url.encodedPath + "?" + request.url.encodedQuery
        val body = request.body
        if (request.method != "GET" && body != null && body.contentLength() > 0L) {
            signatureInput += md5Hex(readBodyText(body)).lowercase(Locale.ROOT)
        }

        val segments = request.url.pathSegments
        if (segments.contains("login") || segments.contains("sms")) {
            return chain.proceed(request)
        }

        return chain.proceed(
            request.newBuilder()
                .header("Authorization", context.authToken())
                .header("Signature", AesCbc.encryptHex(signatureInput))
                .build()
        )
    }

    private fun readBodyText(body: RequestBody): String =
        try {
            Buffer().use { sink ->
                body.writeTo(sink)
                sink.readUtf8()
            }
        } catch (e: IOException) {
            e.stackTraceToString()
        }

    companion object {
        const val WECHAT_HOST = "api.weixin.qq.com"
        const val CLIENT = "android"
    }
}
