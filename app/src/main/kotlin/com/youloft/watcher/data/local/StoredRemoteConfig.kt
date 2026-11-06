package com.youloft.watcher.data.local

import com.google.gson.JsonParser
import com.youloft.linkup.net.ConfigService

/**
 * The read side of the config service, in the recovered shape: the fetcher wrote `Parameter_Map` (and
 * each sibling key) into the settings store, and readers answered from that copy — `ConfigUtils.k` read
 * the stored string, parsed it as JSON and indexed it by key.
 *
 * So this class needs no network. Refreshing the store is [writeConfigFetch], called by whoever owns the
 * fetch — the original called it from `SdkInitManager` at startup (`ConfigUtils.m()`), the rebuild only
 * runs it from the probe screen until we decide to keep a startup job.
 */
class StoredRemoteConfig(
    private val stores: KeyValueStores,
) : RemoteConfig {

    private val parameterMap: com.google.gson.JsonObject? by lazy {
        stores.shared.string(KEY_PARAMETER_MAP)?.let { raw ->
            runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        }
    }

    override fun stringList(key: String): List<String> = runCatching {
        parameterMap?.getAsJsonArray(key)?.map { it.asString }.orEmpty()
    }.getOrDefault(emptyList())

    /**
     * Config values arrive both as JSON numbers and as quoted strings (`of.b` base64-decodes every value
     * before storing it), so both spellings are read and anything unparseable counts as absent — the
     * caller keeps its recovered fallback rather than getting a 0.
     */
    override fun number(key: String): Double? {
        val element = parameterMap?.get(key) ?: return null
        if (element.isJsonNull) return null
        runCatching { element.asString.toDoubleOrNull() }.getOrNull()?.let { return it }
        return runCatching { element.asDouble }.getOrNull()
    }

    /** `SettingProvider.u(context, "online_lastrequest", 0L)` — when the last request went out. */
    fun lastRequestAtMillis(): Long =
        stores.shared.string(KEY_LAST_REQUEST_AT)?.toLongOrNull() ?: 0L

    fun storedAppVersion(): String? = stores.shared.string(KEY_LAST_REQUEST_VER)

    /** `online_lastupdate`, the `r` of the last answer that actually carried values. */
    fun storedLastUpdate(): String? = stores.shared.string(KEY_LAST_UPDATE)

    companion object {
        /** The settings key the recovered client stored the parameter blob under. */
        const val KEY_PARAMETER_MAP = "Parameter_Map"

        /** `of.b` store keys, verbatim (`f76652i` / `f76653j` / `f76654k`). */
        const val KEY_LAST_UPDATE = "online_lastupdate"
        const val KEY_LAST_REQUEST_AT = "online_lastrequest"
        const val KEY_LAST_REQUEST_VER = "online_lastrequest_ver"
    }
}

/**
 * Writer half: stores what a [ConfigService] fetch returned, exactly as the original flattened it —
 * every `msg` key base64-decoded into the store, then the three bookkeeping keys. An empty `msg`
 * ("nothing changed") leaves `Parameter_Map` alone and only stamps the request time, which is what
 * `of.b.o` did.
 */
fun KeyValueStores.writeConfigFetch(fetch: ConfigService.Fetch, appVersion: String, nowMillis: Long) {
    fetch.values.forEach { (key, value) -> shared.putString(key, value) }
    if (fetch.lastUpdate.isNotEmpty()) shared.putString(StoredRemoteConfig.KEY_LAST_UPDATE, fetch.lastUpdate)
    shared.putString(StoredRemoteConfig.KEY_LAST_REQUEST_VER, appVersion)
    shared.putString(StoredRemoteConfig.KEY_LAST_REQUEST_AT, nowMillis.toString())
}

/**
 * The one place that owns the fetch: ask only when the recovered throttle says so, echo the stored
 * token, store the answer. Returns false when nothing was fetched.
 */
fun KeyValueStores.refreshRemoteConfig(service: ConfigService, nowMillis: Long = System.currentTimeMillis()): Boolean {
    val stored = StoredRemoteConfig(this)
    if (!service.shouldFetch(stored.storedAppVersion(), stored.lastRequestAtMillis(), nowMillis)) return false
    val token = service.lastUpdateFor(stored.storedAppVersion(), stored.storedLastUpdate())
    val fetch = service.fetch(token) ?: return false
    writeConfigFetch(fetch, service.appVersion, nowMillis)
    return true
}
