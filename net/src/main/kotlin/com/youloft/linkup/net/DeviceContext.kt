package com.youloft.linkup.net

/** The 11 common query parameters that gh/d.java injects, plus the JWT the same interceptor sends. */
interface DeviceContext {
    val timeZoneOffsetMinutes: Int
    val language: String
    val userId: Long
    val deviceId: String
    val channel: String
    val appVersion: String
    val distinctId: String
    val shushuDeviceId: String
    val mainUser: Long

    fun authToken(): String
}

data class StaticDeviceContext(
    override val timeZoneOffsetMinutes: Int = 480,
    override val language: String = "zh",
    override val userId: Long = 0L,
    override val deviceId: String = "",
    override val channel: String = "official",
    override val appVersion: String = "1.6.6",
    override val distinctId: String = "",
    override val shushuDeviceId: String = "",
    override val mainUser: Long = 0L,
    private val token: String = "",
) : DeviceContext {
    override fun authToken(): String = token
}
