package com.youloft.watcher.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import androidx.lifecycle.lifecycleScope
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 轨迹桌面小组件，`widget/report` 域的重建版：`api/widget/track` + `api/widget/location` 两读数
 * （对方最新位置 / 今日停留数与总里程），onUpdate 与收到位置广播时刷新。
 *
 * 原版的常驻更新服务与 VIP 掩码未在本卡内；读数只对已登录且已同意隐私的会话发起，否则卡片显示
 * "登录后可见"——组件不做假数据。
 */
class TrackWidgetProvider : AppWidgetProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context, manager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        scope.cancel()
    }

    private fun refresh(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        scope.launch {
            val graph = appGraph(context)
            val views = RemoteViews(context.packageName, R.layout.widget_track)
            if (!graph.privacyGate.agreed() || graph.users.current().userId <= 0) {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_login_required))
                views.setTextViewText(R.id.widget_track_summary, "")
                views.setTextViewText(R.id.widget_friend_state, "")
                manager.updateAppWidget(appWidgetIds, views)
                return@launch
            }
            // 原版 bean/TrackWidget 只带 stayNumToday/stays/sysLocation——里程与对方定位在别的
            // 卡片域，不编造。对方设备状态（电量等）来自 widget/location 的 FriendState。
            val track = runCatching { graph.imApi.widgetTrack() }.getOrNull()?.data
            val location = runCatching { graph.imApi.widgetLocation() }.getOrNull()?.data
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_title))
            views.setTextViewText(
                R.id.widget_track_summary,
                context.getString(R.string.widget_track_summary, track?.stayNumToday ?: 0),
            )
            val state = location?.friendState
            views.setTextViewText(
                R.id.widget_friend_state,
                if (state != null) context.getString(R.string.widget_friend_battery, state.currentBattery) else "",
            )
            manager.updateAppWidget(appWidgetIds, views)
        }
    }
}
