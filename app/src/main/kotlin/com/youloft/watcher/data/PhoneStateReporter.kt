package com.youloft.watcher.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import com.youloft.watcher.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * `manager/f1` 的设备自报（`api/phone/state/report`）重建：本机电池/充电/网络/系统版本一次打包
 * 上报，服务端把它落进 DeviceState 台账——lock_list 与实时截屏屏的 av/client/osVersion 行都从
 * 这里来（待验 77 的写入方）。
 *
 * 两个如实标注的取舍：
 * - 原 bean（`bean/PhoneStateInfo`）没有 `av`/`client` 键，原服务端从哪里得知版本不在 APK 里；
 *   本工程把这两个键搭在同一条上报里（服务端同名读取），而不是伪造一个来源。
 * - 原版 f1 还报存储/流量/CPU 等一整套；本重建只报两块屏真实读到的键，未读的键不编造。
 */
class PhoneStateReporter(
    private val scope: CoroutineScope,
    private val report: suspend (MutableMap<String, Any?>) -> Boolean,
) {
    /** One self-report. Failures are swallowed on purpose: a lost report must never crash a login. */
    fun reportOnce(facts: () -> Map<String, Any?>) {
        scope.launch {
            runCatching { report(buildPayload(facts())) }
        }
    }

    companion object {
        /** The wire payload: bean-shaped facts plus the av/client version row this rebuild carries. */
        fun buildPayload(facts: Map<String, Any?>): MutableMap<String, Any?> = mutableMapOf<String, Any?>(
            "currentBattery" to (facts["currentBattery"] as? Int ?: 0),
            "isCharging" to (facts["isCharging"] as? Boolean ?: false),
            "networkType" to (facts["networkType"] as? Int ?: 0),
            "wifi" to (facts["wifi"] as? String ?: ""),
            "osVersion" to (facts["osVersion"] as? String ?: Build.VERSION.RELEASE),
            "model" to (facts["model"] as? String ?: Build.MODEL),
            "brand" to (facts["brand"] as? String ?: Build.BRAND),
            "av" to BuildConfig.VERSION_NAME,
            "client" to "android",
        )
    }
}

/**
 * The on-device fact reader. Charging state comes from the sticky battery intent (no
 * BroadcastReceiver registration needed); network type is the wifi/cellular/none triple the
 * widget cards render — the original's finer enum is not observable from the APK.
 */
object AndroidPhoneFacts {
    fun read(context: Context): Map<String, Any?> {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val networkType = when {
            caps == null -> 0
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
            else -> 0
        }
        val wifi = if (networkType == 1) "wifi" else ""
        return mapOf(
            "currentBattery" to percent,
            "isCharging" to (plugged > 0),
            "networkType" to networkType,
            "wifi" to wifi,
        )
    }
}
