package com.youloft.watcher.data.local

import android.content.Context
import com.tencent.mmkv.MMKV

/**
 * The two MMKV instances the recovered `CacheUtils` reads through:
 * a process-wide default one, and one named after the logged-in user id. Both are opened in
 * multi-process mode (the original passed mode `2`), and both are read through this tiny
 * string-only surface because every value these screens need is a Gson JSON string.
 */
interface KeyValueStore {
    fun string(key: String): String?

    fun putString(key: String, value: String)
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

    override val shared: KeyValueStore = object : KeyValueStore {
        override fun string(key: String): String? = sharedValues[key]
        override fun putString(key: String, value: String) {
            sharedValues[key] = value
        }
    }

    override fun forUser(userId: Long): KeyValueStore {
        val values = userValues.getOrPut(userId) { mutableMapOf() }
        return object : KeyValueStore {
            override fun string(key: String): String? = values[key]
            override fun putString(key: String, value: String) {
                values[key] = value
            }
        }
    }

    fun putForUser(userId: Long, key: String, value: String) {
        userValues.getOrPut(userId) { mutableMapOf() }[key] = value
    }
}
