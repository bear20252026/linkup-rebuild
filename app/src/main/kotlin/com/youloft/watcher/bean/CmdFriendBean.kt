package com.youloft.watcher.bean

/**
 * The friend-change command the IM push layer emits, recovered from `bean/CmdFriendBean.java`.
 * The six type codes are the constants the original class declares; they are what screens filter on.
 */
data class CmdFriendBean(
    val type: Int = 0,
    val userid: Long = 0L,
    val nickName: String? = null,
    val gender: Int = 0,
    val avatar: String? = null,
    val relation: Int = 0,
    var extra: Any? = null,
) {
    companion object {
        const val ADD = 1
        const val ADD_SELF = 100
        const val DELETE = 2
        const val UPDATE = 3
        const val LOGOUT = 4
        const val DELETE_SELF = 101
    }
}
