package com.youloft.watcher.data.local

import android.content.Context
import com.tencent.mmkv.MMKV

/**
 * The two MMKV instances the recovered `CacheUtils` reads through:
 * a process-wide default one, and one named after the logged-in user id. Both are opened in
 * multi-process mode (the original passed mode `2`).
 *
 * Strings carry the JSON records, but `CacheUtils` also wrote plain ints and booleans
 * (`decodeInt("lastLoginType", 0)`, `decodeBool("isRequestedNotification", false)`), so those two
 * types are part of the surface too — an MMKV value written as an int is not readable as a string,
 * and old installs are exactly what a rebuilt client has to keep reading.
 */
interface KeyValueStore {
    fun string(key: String): String?

    fun putString(key: String, value: String)

    fun int(key: String, default: Int = 0): Int

    fun putInt(key: String, value: Int)

    fun boolean(key: String, default: Boolean = false): Boolean

    fun putBoolean(key: String, value: Boolean)
}

interface KeyValueStores {
    /** `MMKV.defaultMMKV(2, null)` — holds `user2`. */
    val shared: KeyValueStore

    /** `MMKV.mmkvWithID(userId.toString(), 2)` — holds that user's `friendList`. */
    fun forUser(userId: Long): KeyValueStore
}

private class MmkvStore(private val mmkv: MMKV) : KeyValueStore {
    override fun string(key: String): String? = mmkv.decodeString(key)
    override fun putString(key: String, value: String) {
        mmkv.encode(key, value)
    }

    override fun int(key: String, default: Int): Int = mmkv.decodeInt(key, default)
    override fun putInt(key: String, value: Int) {
        mmkv.encode(key, value)
    }

    override fun boolean(key: String, default: Boolean): Boolean = mmkv.decodeBool(key, default)
    override fun putBoolean(key: String, value: Boolean) {
        mmkv.encode(key, value)
    }
}

/** Production stores. Initialized once because `MMKV.initialize` needs the app root dir. */
class MmkvKeyValueStores(context: Context) : KeyValueStores {
    private val rootDir = MMKV.initialize(context.applicationContext)
    private val default: KeyValueStore by lazy {
        MmkvStore(MMKV.defaultMMKV(MMKV.MULTI_PROCESS_MODE, null))
    }

    override val shared: KeyValueStore get() = default

    override fun forUser(userId: Long): KeyValueStore =
        MmkvStore(MMKV.mmkvWithID(userId.toString(), MMKV.MULTI_PROCESS_MODE, null))
}

/** Test/dev double: two independent key spaces, no native library involved. */
class InMemoryKeyValueStores(
    initialShared: Map<String, String> = emptyMap(),
    private val userValues: MutableMap<Long, MutableMap<String, String>> = mutableMapOf(),
) : KeyValueStores {
    private val sharedValues = initialShared.toMutableMap()

    override val shared: KeyValueStore = store(sharedValues)

    override fun forUser(userId: Long): KeyValueStore =
        store(userValues.getOrPut(userId) { mutableMapOf() })

    fun putForUser(userId: Long, key: String, value: String) {
        userValues.getOrPut(userId) { mutableMapOf() }[key] = value
    }

    /**
     * One map-backed space. Numbers and flags round-trip through their text form here; the real
     * `MmkvStore` keeps MMKV's own typing, which is what an old install's data needs.
     */
    private fun store(values: MutableMap<String, String>): KeyValueStore = object : KeyValueStore {
        override fun string(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun int(key: String, default: Int): Int = values[key]?.toIntOrNull() ?: default
        override fun putInt(key: String, value: Int) {
            values[key] = value.toString()
        }

        override fun boolean(key: String, default: Boolean): Boolean =
            values[key]?.toBooleanStrictOrNull() ?: default

        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value.toString()
        }
    }
}
