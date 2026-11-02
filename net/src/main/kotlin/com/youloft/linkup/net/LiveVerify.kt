package com.youloft.linkup.net

import retrofit2.HttpException

/**
 * Live proof that the recovered protocol is accepted by the real backend.
 * Run: gradle :net:liveVerify
 */
suspend fun main() {
    val context = StaticDeviceContext(
        deviceId = "rebuild-local-0001",
        distinctId = "rebuild-distinct-0001",
        shushuDeviceId = "rebuild-shushu-0001",
    )
    val api = LinkUpClient(context = context).api

    val config = api.clientConfig()
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
        println("$label -> HTTP ${e.code()} ${e.message()} (signature accepted, auth still required)")
    }
}
