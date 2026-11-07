package com.youloft.watcher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.youloft.linkup.location.LocationCollector
import com.youloft.linkup.location.LocationFix
import com.youloft.linkup.location.LocationProviders
import com.youloft.linkup.location.LocationTuning
import com.youloft.linkup.location.ReportKind
import com.youloft.linkup.location.StayLeaveClassifier
import com.youloft.linkup.net.LinkUpClient
import com.youloft.watcher.AndroidDeviceContext
import com.youloft.watcher.R
import com.youloft.watcher.data.LinkUpLocationGateway
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.data.locationTuning
import com.youloft.watcher.data.preferredProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The foreground service the guard loop lives in — the card the location layer was waiting for.
 *
 * Shape versus the original, both deliberate and written down here because a future reader will look for
 * them: the original registered `com.baidu.location.f` in a `:remote` process (`out_apktool_166` manifest
 * :288) so a location crash could not take the UI down with it; this service runs in the **main process**,
 * because the collector, the gateway and the account cache already live here and a second process would
 * split the MMKV graph on day one of this card. Moving it to `:remote` is a confinement optimization, not
 * a protocol change — nothing in [com.youloft.linkup.location] cares which process it runs in.
 *
 * The service owns one [LocationCollector]: provider from the catalog (the `locationProviderId` setting
 * applied, then the chain), tuning from the remote config, the recovered stay/leave classifier, and the
 * account-aware gateway. Its widget callback only logs — the home-screen widget is its own card, and a
 * log line is what the original's `R()` call degrades to without one.
 *
 * Lifecycle contract for [CollectController]: start via [ACTION_START] (foreground, sticky), stop via
 * [ACTION_STOP]; the service stops itself if no provider can run, because a foreground notification that
 * guards no sensor is a lie.
 */
class TrackCollectService : Service() {

    private var scope: CoroutineScope? = null
    private var collector: LocationCollector? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val graph = appGraph(this)
        val tuning: LocationTuning = graph.remoteConfig.locationTuning()
        val provider = graph.locationProviders.preferredProvider(graph.locationProviderSettings.preferredId)
        if (provider == null) {
            Log.w(TAG, "定位采集无法启动：目录里没有可用的提供方（${graph.locationProviders.describe()}）")
            stopSelf()
            return
        }
        val api = LinkUpClient(
            context = AndroidDeviceContext(applicationContext, graph.accountCache, graph.installChannel),
        ).api
        val classifier = StayLeaveClassifier(tuning)
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = collectorScope
        collector = LocationCollector(
            provider = provider,
            gateway = LinkUpLocationGateway(api),
            tuning = tuning,
            scope = collectorScope,
            onEvent = { event -> Log.d(TAG, event) },
            classifier = classifier,
            // The home-screen widget is its own card; until it exists the copy is auditable in the log.
            onWidget = { fix: LocationFix, kind: ReportKind ->
                Log.d(TAG, "widget 副本 type=${kind.wireType} ${fix.coord.lat},${fix.coord.lng}")
            },
        )
        Log.i(TAG, "采集服务就绪 provider=${provider.id} 节律=${tuning.minIntervalSeconds}-${tuning.maxIntervalSeconds}s")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                startInForeground()
                val started = collector?.start() ?: false
                running = started
                if (!started) {
                    Log.w(TAG, "采集启动失败：提供方拒绝了请求，服务退出")
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        collector?.stop()
        collector = null
        scope?.cancel()
        scope = null
        running = false
        instance = null
        Log.i(TAG, "采集服务退出")
        super.onDestroy()
    }

    /** Foreground/back-foreground switch, driven by [CollectController]'s activity counting. */
    fun setBackgrounded(backgrounded: Boolean) {
        val changed = collector?.setBackgrounded(backgrounded) ?: false
        Log.d(TAG, "采集前后台切换 backgrounded=$backgrounded 已重配=$changed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.guard_location_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.guard_location_notification))
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    companion object {
        private const val TAG = "TrackCollect"

        const val CHANNEL_ID = "guard_location"
        const val NOTIFICATION_ID = 0x6C75 // 'l','u' — stable, and greppable in a heap dump
        const val ACTION_START = "com.youloft.watcher.collect.START"
        const val ACTION_STOP = "com.youloft.watcher.collect.STOP"

        /** The live instance, so the controller can reach the collector without a binder contract. */
        var instance: TrackCollectService? = null
            private set

        /** True from a successful [onStartCommand] start until [onDestroy]. */
        var running: Boolean = false
            private set
    }
}
