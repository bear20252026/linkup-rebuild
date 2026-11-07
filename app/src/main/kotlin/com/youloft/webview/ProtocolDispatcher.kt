package com.youloft.webview

import java.net.URLDecoder

/**
 * `jh.h`（协议分发器）的重建：`protocol://<cmd>?<params>` 的解析与分发。H5 侧的真实调用面：
 *  - `location.href = "protocol://cmd?k=v&k2=v2"`（URL 拦截，异步）；
 *  - `confirm("protocol://cmd?...")`（onJsConfirm 拦截）；
 *  - `prompt("__invoke__" + envelopeJson)`（同步取返回值）。
 *
 * 解析规则逐条照搬 `jh.h.g`：`://` 切 schema 与余段；余段在第一个 `?`/`:`/`#` 处切命令与参数串；
 * 参数串按 JSON 对象 → k=v& 串 → `#` 分段三种形态解出 args；解不出 args 为空 map。envelope 五元组
 * {schema, method, args, argString, cmd} 与原版字段一一对应（原版键名被混淆常量盖住，语义同源）。
 */
class ProtocolDispatcher(private val handler: Handler) {

    /** 原版 envelope；`method` 即命令名（原版 "method"/"_cmd" 两键的语义分开保留）。 */
    data class Envelope(
        val schema: String,
        val method: String,
        val args: Map<String, String>,
        val argString: String,
        val cmd: String,
    )

    fun interface Handler {
        /** 原版 `jh.a.a(envelope)`；返回值同步进 prompt 调用方（`"-unsupport-"` 同原版缺省）。 */
        fun invoke(envelope: Envelope): Any?
    }

    /** `jh.h.a` — 本分发器只认 `protocol://`。 */
    fun matches(url: String): Boolean = url.startsWith("protocol://")

    /** `jh.h.c` — URL 拦截入口（异步路径）；解析后交给 handler。 */
    fun dispatchUrl(url: String) {
        val envelope = parse(url)
        handler.invoke(envelope)
    }

    /** `jh.h.g` — 解析与形态判定（@VisibleForTesting 的纯函数位）。 */
    fun parse(url: String): Envelope {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) {
            return Envelope("protocol", "", emptyMap(), "", "")
        }
        val schema = url.substring(0, schemeEnd)
        val rest = url.substring(schemeEnd + 3)
        val cut = rest.indexOfFirst { it == '?' || it == ':' || it == '#' }.let { if (it >= 0) it else rest.length }
        val cmd = rest.substring(0, cut)
        val rawArgs = if (cut < rest.length) rest.substring(cut + 1) else ""

        val decoded = runCatching { URLDecoder.decode(rawArgs, "utf-8") }.getOrDefault(rawArgs)
        val args: Map<String, String> = when {
            decoded.isNotBlank() && decoded.startsWith("{") ->
                runCatching { parseJsonObject(decoded) }.getOrDefault(emptyMap())
            rest.contains('?') && rawArgs.contains('=') -> parseKeyValue(rawArgs)
            decoded.isBlank() -> emptyMap()
            else -> parseHashFragments(decoded)
        }
        return Envelope(schema = schema, method = cmd, args = args, argString = rawArgs, cmd = rest)
    }

    private fun parseKeyValue(raw: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (pair in raw.split("&")) {
            val idx = pair.indexOf('=')
            if (idx in 1..pair.length) {
                out[pair.substring(0, idx)] =
                    runCatching { URLDecoder.decode(pair.substring(idx + 1), "utf-8") }.getOrDefault(pair.substring(idx + 1))
            }
        }
        return out
    }

    /** `#` 分段 → {"0": seg0, "1": seg1, ...}（原版同形）。 */
    private fun parseHashFragments(decoded: String): Map<String, String> {
        if (!decoded.contains("#")) return mapOf("0" to decoded)
        val parts = decoded.split("#")
        val out = mutableMapOf<String, String>()
        parts.forEachIndexed { index, part -> out[index.toString()] = part }
        return out
    }

    /** 扁平 JSON 对象的字符串值；嵌套结构整体转字符串（原版 fastjson JSONObject 的取值面）。 */
    private fun parseJsonObject(text: String): Map<String, String> {
        val tree = com.google.gson.JsonParser.parseString(text).asJsonObject
        val out = mutableMapOf<String, String>()
        for ((key, value) in tree.entrySet()) {
            out[key] = when {
                value.isJsonPrimitive -> value.asJsonPrimitive.toString().trim('"')
                else -> value.toString()
            }
        }
        return out
    }
}
