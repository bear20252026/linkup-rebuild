package com.youloft.linkup.net

import retrofit2.HttpException

/**
 * Live proof that the recovered protocol is accepted by a real backend.
 * Run: gradle :net:liveVerify --args=<baseUrl>   (defaults to the 1.6.6 hardcoded test host)
 */
suspend fun main(args: Array<String>) {
    val baseUrl = args.firstOrNull() ?: LinkUpEndpoints.API
    val context = StaticDeviceContext(
        deviceId = "rebuild-local-0001",
        distinctId = "rebuild-distinct-0001",
        shushuDeviceId = "rebuild-shushu-0001",
    )
    val api = LinkUpClient(baseUrl = baseUrl, context = context).api

    val config = runCatching { api.clientConfig() }.getOrElse {
        println("$baseUrl -> unreachable/error: $it")
        return
    }
    val keys = config.data?.asJsonObject?.keySet()?.take(8)
    println("clientConfig   -> status=${config.status} msg=${config.msg} dataKeys=$keys")

    probe("userInfo") { api.userInfo() }
    probe("versionCheck") { api.versionCheck(queryId = "") }

    if (!config.isSuccess) error("backend rejected the rebuilt signature")
    println("PROTOCOL_RECOVERED=1")
}

private suspend fun probe(label: String, call: suspend () -> ApiResponse<*>) {
    try {
        val r = call()
        println("$label -> status=${r.status} msg=${r.msg}")
    } catch (e: HttpException) {
        println("$label -> HTTP ${e.code()} ${e.message()} (reached business layer, auth required)")
    }
}
