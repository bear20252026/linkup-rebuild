package com.youloft.watcher

import com.youloft.watcher.data.LinkUpApplication
import com.youloft.watcher.data.local.InMemoryKeyValueStores

/**
 * For UI-inflation tests that never read a store: swaps the production MMKV graph for the in-memory
 * double, because the native library cannot load on the JVM and the collection lifecycle (which now
 * evaluates its gates on every resume) legitimately reads the consent flag.
 *
 * Tests that need to script store contents keep their own fixture applications; this one only exists so
 * "inflate real layouts" tests stay hermetic.
 */
open class StorelessApp : LinkUpApplication() {
    override val keyValueStores = InMemoryKeyValueStores()
}
