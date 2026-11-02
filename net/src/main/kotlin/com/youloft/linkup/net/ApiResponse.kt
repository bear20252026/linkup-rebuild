package com.youloft.linkup.net

/** Envelope observed on the live server: {"status":200,"msg":"success","data":{...}} */
data class ApiResponse<T>(
    val status: Int = 0,
    val msg: String? = null,
    val reason: String? = null,
    val data: T? = null,
) {
    val isSuccess: Boolean get() = status == 200
}
