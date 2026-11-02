package com.youloft.linkup.net

import com.youloft.linkup.api.dto.ChannelConfig
import retrofit2.HttpException

/**
 * Live proof that the recovered protocol and the recovered response models fit the real backend.
 * Run: gradle :net:liveVerify --args=<baseUrl>   (defaults to the production gateway)
 */
suspend fun main(args: Array<String>) {
    val requested = args.firstOrNull() ?: LinkUpEndpoints.PRODUCTION
    val context = StaticDeviceContext(
        deviceId = "rebuild-local-0001",
        distinctId = "rebuild-distinct-0001",
        shushuDeviceId = "rebuild-shushu-0001",
    )

    val hosts = buildList {
        add(requested)
        if (requested.trimEnd('/') != LinkUpEndpoints.API.trimEnd('/')) add(LinkUpEndpoints.API)
    }

    var lastError: Throwable? = null
    for (base in hosts) {
        val api = LinkUpClient(baseUrl = base, context = context).api
        val config = runCatching { api.clientConfig() }.getOrElse {
            lastError = it
            println("$base -> not reachable ($it), trying next host")
            continue
        }
        report(base, config, api)
        if (!config.isSuccess) error("backend rejected the rebuilt signature")
        println("PROTOCOL_RECOVERED=1")
        return
    }
    error("no gateway answered: $hosts (last error: $lastError)")
}

private suspend fun report(base: String, config: ApiResponse<ChannelConfig>, api: LinkUpApi) {
    println("base           -> $base")
    println("clientConfig   -> status=${config.status} msg=${config.msg}")
    config.data?.let { d ->
        println("                 notRelativesLockShow=${d.notRelativesLockShow}")
        println("                 autoReportAddressShow=${d.autoReportAddressShow}")
        println("                 imGroupHuawei=${d.imGroupHuawei} imGroupOppo=${d.imGroupOppo}")
    }
    probe("userInfo") { api.userInfo() }
    probe("versionCheck") { api.versionCheck(queryId = "") }
}

private suspend fun probe(label: String, call: suspend () -> ApiResponse<*>) {
    try {
        val r = call()
        println("$label -> status=${r.status} msg=${r.msg}")
    } catch (e: HttpException) {
        println("$label -> HTTP ${e.code()} ${e.message()} (reached business layer, auth required)")
    }
}
