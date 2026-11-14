package com.youloft.watcher.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.youloft.linkup.api.dto.SensBean
import com.youloft.linkup.api.dto.SensType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 敏感行为域的采集端（DESIGN 五点四十一，监控数据面第三域）：系统广播的**应用安装/卸载/更新**
 * 逐条上报到 `api/sensitive/report`，守护端的敏感行为卡与列表读数从台账来。系统广播不需要权限；
 * type 常量是本重建口径（[SensType]，原版类型表不可恢复）。
 *
 * 如实记录的局限：**未登录期间的安装/卸载事件不回补**——隐私门未过或会话不在时静默丢弃，不做
 * 离线队列（拒绝伪造一条"补报"管线）；解锁卡机场景的真机覆盖见待验 89。
 */
class SensitiveEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val graph = runCatching { appGraph(context) }.getOrNull() ?: return
        val type = sensTypeOf(intent.action ?: return) ?: return
        val pkg = intent.data?.schemeSpecificPart ?: return
        // 采样即裁剪：隐私未同意或未登录的机器不产生敏感事件台账。
        if (!graph.privacyGate.agreed() || graph.users.current().userId <= 0L) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val appName = if (type == SensType.APP_UNINSTALL) {
                    pkg // 卸载后 label 已不可查，包名就是诚实答案
                } else {
                    runCatching {
                        val pm = context.packageManager
                        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString().takeIf { it.isNotBlank() }
                    }.getOrNull() ?: pkg
                }
                runCatching {
                    graph.imApi.sensitiveReport(
                        SensBean(type = type, eventTime = System.currentTimeMillis() / 1000, appName = appName),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** 系统广播 action → 本重建的事件类型；其余 action（如自身包的替换）不产生台账行。 */
internal fun sensTypeOf(action: String): Int? = when (action) {
    Intent.ACTION_PACKAGE_ADDED -> SensType.APP_INSTALL
    Intent.ACTION_PACKAGE_REMOVED -> SensType.APP_UNINSTALL
    Intent.ACTION_PACKAGE_REPLACED -> SensType.APP_UPDATE
    else -> null
}
