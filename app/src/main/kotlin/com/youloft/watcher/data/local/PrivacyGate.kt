package com.youloft.watcher.data.local

/**
 * `CacheUtils.M0()` / `r1(boolean)` — the install-level privacy consent flag. It lives in the default
 * MMKV instance under `isAgreePrivacyPolicy` and defaults to false, which is what routes a fresh install
 * through `EmptyLoginActivity` before any login. Only an explicit "同意并进入" writes it; dismissing or
 * disagreeing never does.
 */
class PrivacyGate(private val stores: KeyValueStores) {

    fun agreed(): Boolean = stores.shared.boolean(KEY_AGREE_PRIVACY_POLICY)

    fun agree() {
        stores.shared.putBoolean(KEY_AGREE_PRIVACY_POLICY, true)
    }

    companion object {
        const val KEY_AGREE_PRIVACY_POLICY = "isAgreePrivacyPolicy"
    }
}