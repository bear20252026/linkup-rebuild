package com.youloft.watcher.bean

import com.google.gson.Gson

/**
 * `bean/CmdScreenShot`——随 IM 命令 `screenShot`（166 新增）的 data JSON travels 的载荷。
 * 语义（`pages/shot/j0` 状态机 + `widget/im/f` 收发两端核对）：
 * - **发起端** → `step=1, callBack=0`：请求一帧。
 * - **收端回执**（`callBack=1`）：step 1 = 权限就绪与否（[accessibilityAvailable]）；
 *   step 2 = 采集成败（[screenShotSuccess]）；step 3 = [screenShotImage] 带图 URL。
 */
data class CmdScreenShot(
    val userid: Long = 0L,
    val step: Int = 0,
    val callBack: Int = 0,
    val accessibilityAvailable: Boolean = false,
    val screenShotSuccess: Boolean = false,
    val screenShotImage: String? = null,
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): CmdScreenShot? =
            runCatching { Gson().fromJson(json, CmdScreenShot::class.java) }.getOrNull()
    }
}
