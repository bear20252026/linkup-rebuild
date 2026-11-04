package com.youloft.linkup.net

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The remote config service, identified in #17.
 *
 * It is **not** a third-party SDK: the recovered client (`of.b` in 1.5.6, `nf.b` in 1.6.6) is a plain
 * OkHttp GET against `cfg.51wnl-cq.com`, a domain that belongs to us. As of 2026-11-04 that address
 * answers `302` and forwards to the current service, `configsys.51wnl-cq.com/api/config/getconfigs`,
 * so the new address is what this client uses.
 *
 * Shape of the answer, from live read-only calls on 2026-11-04 (captured in
 * `recovered/live_config_sample_2026-11-04.json`):
 *
 * ```
 * { "status": 1, "r": 17533440860101, "rver": "156_1753344086_101010100",
 *   "msg": { "Parameter_Map": "<base64 JSON>" } }
 * ```
 *
 * Every value in `msg` is base64 (the recovered `of.b.d` is `Base64.decode(str, DEFAULT)`), and the app
 * flattens each key into its settings store; `ConfigUtils.showPermissionChannel` therefore read
 * `Parameter_Map → showPermissionChannel` as a JSON array of strings.
 *
 * `r` is the change token: asking again with `lastupdate=<r>` answers `{"msg":{}, "status":1}` — measured
 * the same day. The original echoed it back only while the stored app version still matched
 * (`of.b.o`: a version mismatch clears the token and re-asks for everything).
 *
 * `params` carries the device identity the recovered interceptor appended (`of.b.l` + `of.c`):
 * `bd` = package name, `cid` = the literal `Youloft_Android`, `av` = version name, `chn` = the install
 * channel plus `"-0"`, `ov` = `Build.VERSION.RELEASE`, `did`/`cityCode` were left empty. None of them
 * are required — the same answer came back without them — they only steer per-channel targeting.
 */
class ConfigService(
    private val client: OkHttpClient = OkHttpClient(),
    private val appId: String = APP_ID,
    val appVersion: String = APP_VERSION,
    private val params: Map<String, String> = emptyMap(),
) {

    /** `lastUpdate` is the `r` from a previous fetch; empty asks for everything. */
    fun url(lastUpdate: String = ""): String = ENDPOINT.toHttpUrl().newBuilder()
        .addQueryParameter("appid", appId)
        .addQueryParameter("appver", appVersion)
        .addQueryParameter("lastupdate", lastUpdate)
        .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
        .build()
        .toString()

    fun fetch(lastUpdate: String = ""): Fetch? {
        val body = runCatching {
            client.newCall(Request.Builder().url(url(lastUpdate)).get().build()).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull() ?: return null
        return parse(body)
    }

    /** Split out so a captured response can be tested without a network. */
    internal fun parse(body: String): Fetch? = runCatching {
        val envelope = JsonParser.parseString(body).asJsonObject
        if (envelope.intOrNull("status") != STATUS_OK) return null
        val msg = envelope.getAsJsonObject("msg") ?: return null
        val values = msg.entrySet().associate { (key, element) -> key to decodeValue(element.asString) }
        Fetch(
            values = values,
            parameterMap = (values[PARAMETER_MAP]?.let { JsonParser.parseString(it).asJsonObject }) ?: JsonObject(),
            lastUpdate = envelope.get("r")?.asString.orEmpty(),
        )
    }.getOrNull()

    /** `of.b.d()`: base64, except an empty value which is stored as-is. */
    internal fun decodeValue(raw: String): String =
        if (raw.isEmpty()) raw else runCatching { String(Base64.getDecoder().decode(raw), Charsets.UTF_8) }.getOrDefault(raw)

    /** The string array behind a `Parameter_Map` key, e.g. `showPermissionChannel`. */
    fun stringList(fetch: Fetch, key: String): List<String> = runCatching {
        fetch.parameterMap.getAsJsonArray(key)?.map { it.asString }.orEmpty()
    }.getOrDefault(emptyList())

    /**
     * `of.b.o()` skipped the request while `abs(lastRequestAt - now) < 600000`, unless the stored app
     * version differed from the current one — that case drops the token and asks for everything.
     */
    fun shouldFetch(storedAppVersion: String?, lastRequestAtMillis: Long, nowMillis: Long): Boolean =
        storedAppVersion != appVersion || isStale(lastRequestAtMillis, nowMillis)

    /** The token to echo: `of.b` cleared it whenever the app version changed. */
    fun lastUpdateFor(storedAppVersion: String?, storedLastUpdate: String?): String =
        if (storedAppVersion == appVersion) storedLastUpdate.orEmpty() else ""

    /** Anything older than the original's ten-minute window is worth re-asking. */
    fun isStale(lastFetchAtMillis: Long, nowMillis: Long): Boolean =
        kotlin.math.abs(nowMillis - lastFetchAtMillis) >= REFRESH_INTERVAL_MILLIS

    class Fetch(val values: Map<String, String>, val parameterMap: JsonObject, val lastUpdate: String)

    companion object {
        const val ENDPOINT = "https://configsys.51wnl-cq.com/api/config/getconfigs"
        const val LEGACY_ENDPOINT = "https://cfg.51wnl-cq.com/api/getallconfig.ashx"
        const val APP_ID = "android-watcher"

        /** 1.5.6 sent `"156"`, 1.6.6 sends `"166"` (`nf.b.f("android-watcher","166")`). */
        const val APP_VERSION = "166"
        const val LEGACY_APP_VERSION = "156"

        /** The literal `of.c.f76667c` every config request carried. */
        const val CLIENT_ID = "Youloft_Android"
        const val STATUS_OK = 1
        const val PARAMETER_MAP = "Parameter_Map"
        const val REFRESH_INTERVAL_MILLIS = 600_000L

        private fun JsonObject.intOrNull(key: String): Int? =
            get(key)?.takeIf { it.isJsonPrimitive }?.let { it.asNumber.toInt() }
    }
}
