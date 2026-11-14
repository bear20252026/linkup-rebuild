package com.youloft.watcher.service

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.youloft.linkup.location.provider.system.LocationPermissions
import com.youloft.linkup.location.canLocate

/**
 * The three gates between the app and a running collection service, and the switch that operates it.
 *
 * The recovered app started its manager as soon as the privacy gate opened (`SdkInitManager` ran
 * `SDKInitializer.setAgreePrivacy(true)` and built the client); it never had to ask whether a user was
 * signed in, because the service ran for the install, not the account. This rebuild keeps the consent gate
 * but adds the two the rebuilt backend makes real: fixes without an account cannot be filed (the gateway's
 * report route is behind the JWT), and fixes without a location permission cannot exist. All three are
 * evaluated on every [refresh], so any of them changing — consent given, permission granted, login or
 * sign-out — flips the service at the next lifecycle transition.
 *
 * Pure state plus two injected side effects ([startService]/[stopService]), which is why the gate table is
 * testable without Robolectric; [forProduction] is the only place that knows about intents.
 */
class CollectController internal constructor(
    private val startService: () -> Unit,
    private val stopService: () -> Unit,
    private val gates: Gates,
) {

    /** Consent, permission, account. Every one must hold before the service runs. */
    data class Gates(
        val privacyAgreed: () -> Boolean,
        val canLocate: () -> Boolean,
        val signedIn: () -> Boolean,
    ) {
        fun allPass(): Boolean = privacyAgreed() && canLocate() && signedIn()
    }

    /** What the last [refresh] decided; also the guard against re-issuing a start that already went out. */
    var desired: Boolean = false
        private set

    /** Whether the app is currently without a resumed activity (and the collector should go dormant). */
    var backgrounded: Boolean = false
        private set

    private var resumedActivities = 0

    /** Recomputes the decision; only a change issues a service command. */
    fun refresh() {
        val want = gates.allPass()
        if (want == desired) return
        desired = want
        if (want) startService() else stopService()
    }

    /** Call from [Application.registerActivityLifecycleCallbacks]; also re-runs the gate on every transition. */
    fun onActivityResumed() {
        resumedActivities += 1
        if (resumedActivities == 1) backgrounded = false
        TrackCollectService.instance?.setBackgrounded(backgrounded)
        refresh()
    }

    fun onActivityPaused() {
        val before = resumedActivities
        resumedActivities = maxOf(0, resumedActivities - 1)
        if (before == 1 && resumedActivities == 0) {
            backgrounded = true
            TrackCollectService.instance?.setBackgrounded(true)
        }
    }

    /** Registers the lifecycle counting; call once from the application's [Application.onCreate]. */
    fun attach(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) = this@CollectController.onActivityResumed()

                override fun onActivityPaused(activity: Activity) = this@CollectController.onActivityPaused()

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    companion object {
        /** The production controller: real intents, real gates, one instance per app graph. */
        fun forProduction(context: Context, gates: Gates): CollectController = CollectController(
            startService = {
                // Android 12+ 后台启动 FGS 可抛 ForegroundServiceStartNotAllowedException（登录链
                // 在后台冷启动时触发）；采集晚一轮再起没关系，不能把登录压崩（审计⑤）。
                runCatching {
                    context.startForegroundService(
                        Intent(context, TrackCollectService::class.java).setAction(TrackCollectService.ACTION_START),
                    )
                }
            },
            stopService = { context.stopService(Intent(context, TrackCollectService::class.java)) },
            gates = gates,
        )
    }
}
