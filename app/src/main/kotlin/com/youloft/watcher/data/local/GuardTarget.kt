package com.youloft.watcher.data.local

/**
 * 守护对象的切换存储，对应原版 `CacheUtils.U()`（读当前守护对象）与 `u.H(userId)`（切换）。
 * 原版由守护管理器落 MMKV；本工程同一键语义：0 = 没有守护对象（体验模式），正数 = 对方 userId。
 */
class GuardTarget(private val stores: KeyValueStores) {

    fun current(): Long = stores.shared.long(KEY_GUARD_USER, 0L)

    fun switchTo(userId: Long) {
        stores.shared.putLong(KEY_GUARD_USER, userId)
    }

    companion object {
        const val KEY_GUARD_USER = "guardUserId"
    }
}
