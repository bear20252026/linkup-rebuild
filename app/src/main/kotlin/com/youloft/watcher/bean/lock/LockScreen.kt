package com.youloft.watcher.bean.lock

import com.google.gson.Gson

/**
 * `bean/lock/LockScreen`——随 IM 命令 `lockMachine` 的 data JSON  travels 的载荷。
 * `lockType`：1 = 锁机设置发起，2 = 列表行远程锁，3 = 解锁/问题变体。
 * 服务端锁机台账（`api/relation/lock*`）与本载荷由发起端同时发出，与原版 CmdMsgManager 语义一致。
 */
data class LockScreen(
    val userid: Long = 0L,
    val nickName: String? = null,
    val avatar: String? = null,
    val lockType: Int = 0,
    val lockTitle: String? = null,
    val lockBackPic: String? = null,
    val question: com.youloft.linkup.api.dto.QuestionBean? = null,
    val tinyimg: String? = null,
    val lockDuration: Long = 0L,
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        /** 收端解析；格式不对返回 null（原版对 data 的容错一致）。 */
        fun fromJson(json: String): LockScreen? =
            runCatching { Gson().fromJson(json, LockScreen::class.java) }.getOrNull()
    }
}
