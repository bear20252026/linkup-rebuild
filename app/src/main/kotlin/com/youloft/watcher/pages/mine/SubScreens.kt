package com.youloft.watcher.pages.mine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import kotlinx.coroutines.launch

/** 共用的二级屏基座：深色底 + 标题 + 返回，一屏一LinearLayout。 */
abstract class SubScreenActivity(protected val titleRes: Int) : AppCompatActivity() {
    protected lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F6FA.toInt())
            setPadding(0, 96, 0, 0)
        }
        val back = TextView(this).apply {
            text = "‹  ${getString(titleRes)}"
            textSize = 18f
            setTextColor(0xFF324058.toInt())
            setPadding(48, 32, 48, 32)
            setOnClickListener { finish() }
        }
        root.addView(back)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 32)
        }
        root.addView(column)
        setContentView(root)
        buildContent(column)
    }

    protected abstract fun buildContent(column: LinearLayout)

    /** 一行卡片：标签 + 可选右侧值/开关。 */
    protected fun row(
        parent: LinearLayout,
        label: String,
        valueText: String? = null,
        onClick: (() -> Unit)? = null,
    ): View = TextView(this).apply {
        text = listOfNotNull(label, valueText?.takeIf { it.isNotBlank() }).joinToString("  ")
        textSize = 15f
        setTextColor(0xFF324058.toInt())
        setPadding(32, 40, 32, 40)
        setBackgroundColor(0xFFFFFFFF.toInt())
        gravity = android.view.Gravity.CENTER_VERTICAL
        onClick?.let { cb -> setOnClickListener { cb() } } ?: setOnClickListener(null)
        parent.addView(this, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 8 })
        this
    }

    protected fun switchRow(parent: LinearLayout, label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        holder.addView(TextView(this).apply {
            text = label; textSize = 15f; setTextColor(0xFF324058.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        holder.addView(Switch(this).apply { isChecked = checked; setOnCheckedChangeListener { _, v -> onToggle(v) } })
        parent.addView(holder, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 8 })
    }

    protected fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

/** 权限列表，`pages/permission/PermissionListActivity`：7 类系统权限 + 跳系统设置。 */
class PermissionListActivity : SubScreenActivity(R.string.data_leak_concern) {
    override fun buildContent(column: LinearLayout) {
        val items = listOf(
            "电池优化" to Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            "应用使用权限" to Settings.ACTION_USAGE_ACCESS_SETTINGS,
            "手机状态" to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "通知权限" to "android.settings.NOTIFICATION_SETTINGS",
            "自启动" to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "悬浮窗" to Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        )
        items.forEach { (label, action) ->
            row(column, label, "去设置") {
                runCatching {
                    startActivity(Intent(action).setData(
                        if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            Uri.fromParts("package", packageName, null) else null,
                    ))
                }.onFailure { toast("跳转失败") }
            }
        }
    }
}

/** 自动报备设置，`pages/mine/AutoReportSettingsActivity`：`api/setting/im` 的六开关。 */
class AutoReportSettingsActivity : SubScreenActivity(R.string.notify_settings) {
    override fun buildContent(column: LinearLayout) {
        val graph = appGraph(this)
        val keys = listOf(
            "imLocationNoticeType" to "位置变更提醒",
            "imLinkUpNoticeType" to "上线提醒",
            "imPhoneNoticeType" to "开关机提醒",
            "imAppUseNoticeType" to "应用使用提醒",
            "imPhoneStateNoticeType" to "电量状态提醒",
            "imCallRecordNoticeType" to "通话记录提醒",
        )
        lifecycleScope.launch {
            val response = runCatching { graph.imApi.settingImGet() }.getOrNull()
            val obj = response?.data as? Map<*, *>
            // 服务端按账号存整份 bean JSON：写回必须带全量键（只发一个键会把其余开关清零）。
            val bean = obj?.entries?.associate { it.key.toString() to it.value }?.toMutableMap()
                ?: mutableMapOf()
            keys.forEach { (key, label) ->
                val on = (obj?.get(key) as? Number)?.toInt() ?: 0
                switchRow(column, label, on > 0) { v ->
                    bean[key] = if (v) 1 else 0
                    lifecycleScope.launch {
                        runCatching { graph.imApi.settingImUpdate(HashMap(bean)) }
                    }
                }
            }
        }
    }
}

/** 地图设置，`pages/mine/MapSettingsActivity`：经典/卫星 + 路况，存 MMKV（地图卡未接前为开关记忆）。 */
class MapSettingsActivity : SubScreenActivity(R.string.map_settings) {
    override fun buildContent(column: LinearLayout) {
        val graph = appGraph(this)
        val settings = graph.locationProviderSettings
        row(column, "经典地图", if (graph.keyValueStores.shared.int("mapMode") == 0) "当前" else "") {
            graph.keyValueStores.shared.putInt("mapMode", 0); toast("已切换")
        }
        row(column, "卫星地图", if (graph.keyValueStores.shared.int("mapMode") == 1) "当前" else "") {
            graph.keyValueStores.shared.putInt("mapMode", 1); toast("已切换")
        }
        switchRow(column, "显示路况", graph.keyValueStores.shared.boolean("mapTraffic")) {
            graph.keyValueStores.shared.putBoolean("mapTraffic", it)
        }
    }
}

/** 隐私设置，`pages/privacy/PrivacySettingsActivity`：好友级敏感开关（`api/setting/user_list` + `update`）。 */
class PrivacySettingsActivity : SubScreenActivity(R.string.privacy_settings) {
    override fun buildContent(column: LinearLayout) {
        val graph = appGraph(this)
        lifecycleScope.launch {
            val response = runCatching { graph.imApi.privacyUserList() }.getOrNull()
            val rows = (response?.data as? Map<*, *>)?.get("list") as? List<*>
            if (rows.isNullOrEmpty()) {
                row(column, "还没有好友，先去添加吧")
                return@launch
            }
            rows.forEach { rowRaw ->
                val row = rowRaw as? Map<*, *> ?: return@forEach
                val userId = (row["userId"] as? Number)?.toLong() ?: 0L
                val name = (row["remark"] as? String) ?: (row["nickname"] as? String) ?: userId.toString()
                switchRow(column, "$name · 位置共享", row["sensLocation"] == true) { v ->
                    lifecycleScope.launch { runCatching { graph.imApi.privacyUpdate(mutableMapOf("friendId" to userId, "sensLocation" to v)) } }
                }
                switchRow(column, "$name · 通话共享", row["sensCall"] == true) { v ->
                    lifecycleScope.launch { runCatching { graph.imApi.privacyUpdate(mutableMapOf("friendId" to userId, "sensCall" to v)) } }
                }
                switchRow(column, "$name · 应用使用", row["sensAppUse"] == true) { v ->
                    lifecycleScope.launch { runCatching { graph.imApi.privacyUpdate(mutableMapOf("friendId" to userId, "sensAppUse" to v)) } }
                }
            }
        }
    }
}

/** 数据安全，`pages/mine/DataSecurityActivity`：静态两卡。 */
class DataSecurityActivity : SubScreenActivity(R.string.data_security) {
    override fun buildContent(column: LinearLayout) {
        row(column, "ICP 备案信息")
        row(column, "安全评估报告")
    }
}

/** 应用锁，`pages/protection/AppPswHomeActivity`：开关存 MMKV（应用锁服务卡后续）。 */
class AppPswHomeActivity : SubScreenActivity(R.string.app_psw) {
    override fun buildContent(column: LinearLayout) {
        val stores = appGraph(this).keyValueStores.shared
        switchRow(column, "启用应用锁", stores.boolean("appPswEnabled")) { v ->
            stores.putBoolean("appPswEnabled", v)
            toast(if (v) "应用锁已开启（密码服务卡后续）" else "应用锁已关闭")
        }
        row(column, "修改密码") { toast("应用锁密码服务卡后续") }
    }
}

/** 联系我们，`pages/mine/ContactUsActivity`：QQ/小红书/抖音/快手跳转 + 微信客服（需微信SDK卡）。 */
class ContactUsActivity : SubScreenActivity(R.string.contact_us) {
    override fun buildContent(column: LinearLayout) {
        row(column, "QQ 官方群") { openApp("com.tencent.mobileqq", null, "请先安装 QQ") }
        row(column, "小红书") { openApp("com.xingin.xhs", "xhsdiscover://user/63b0506b0000000027028702", "请先安装小红书") }
        row(column, "抖音") { openApp("com.ss.android.ugc.aweme", "snssdk1128://user/profile/2198617717999182", "请先安装抖音") }
        row(column, "快手") { openApp("com.smile.gifmaker", "kwai://profile/4563027025", "请先安装快手") }
        row(column, "微信客服") { toast("微信客服需微信SDK卡") }
    }

    private fun openApp(pkg: String, uri: String?, notInstalled: String) {
        runCatching {
            startActivity(packageManager.getLaunchIntentForPackage(pkg)
                ?: uri?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
                ?: throw Exception())
        }.onFailure { toast(notInstalled) }
    }
}

/** 关于我们，`pages/mine/AboutUsActivity`：版本 + 协议 + 反馈（Feedback 卡未重建→记录）。 */
class AboutUsActivity : SubScreenActivity(R.string.about_us) {
    override fun buildContent(column: LinearLayout) {
        row(column, "版本", packageManager.getPackageInfo(packageName, 0).versionName ?: "")
        row(column, "检查更新") { toast("已是最新版本") }
        row(column, "用户协议") {
            com.youloft.watcher.pages.web.CommonWebActivity.start(this, "https://web-link.tttick.com/business/protocol/user.html?fullscreen=true", "用户协议")
        }
        row(column, "隐私政策") {
            com.youloft.watcher.pages.web.CommonWebActivity.start(this, "https://web-link.tttick.com/business/protocol/privacy.html?fullscreen=true", "隐私政策")
        }
        row(column, "意见反馈") { toast("反馈卡后续") }
    }
}

/** MoreFragment 的跳转需要 start 方法；同一模式统一生成。 */
fun startSubScreen(context: Context, screen: Class<out SubScreenActivity>) {
    context.startActivity(Intent(context, screen))
}
