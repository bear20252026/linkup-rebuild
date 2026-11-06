package com.youloft.watcher

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.youloft.linkup.location.LocationRequest
import com.youloft.linkup.location.LocationResult
import com.youloft.linkup.location.provider.system.LocationPermissions
import com.youloft.linkup.location.provider.system.SystemPlaceResolver
import com.youloft.linkup.net.LinkUpClient
import com.youloft.watcher.data.LinkUpApplication
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.data.locationProviders
import com.youloft.watcher.data.local.PermissionChannelGate
import com.youloft.watcher.data.local.SigningBlockInstallChannel
import com.youloft.watcher.data.local.StoredRemoteConfig
import com.youloft.watcher.data.local.isVip
import com.youloft.watcher.data.local.refreshRemoteConfig
import com.youloft.watcher.data.locationTuning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Entry point: proves the recovered signing stack and the recovered theme run on Android. */
class RecoveryProbeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recovery_probe)
        val result = findViewById<TextView>(R.id.probe_result)
        result.text = "probing $LINKUP_HOST ..."

        val context = AndroidDeviceContext(this)
        val graph = appGraph(this)
        lifecycleScope.launch {
            result.text = runCatching {
                val response = LinkUpClient(context = context).api.clientConfig()
                buildString {
                    appendLine("status=${response.status} msg=${response.msg}")
                    appendLine("signature accepted by backend: ${response.isSuccess}")
                    appendLine("appcompat theme applied: ${themeResolved()}")
                    response.data?.let {
                        appendLine("notRelativesLockShow=${it.notRelativesLockShow}")
                        appendLine("autoReportAddressShow=${it.autoReportAddressShow}")
                        appendLine("imGroup huawei=${it.imGroupHuawei} oppo=${it.imGroupOppo}")
                    }
                    // The account write path, so a device check can confirm that a fetched user
                    // lands in MMKV under the recovered keys and feeds isVip / the request identity.
                    val users = graph.users
                    appendLine("---- GET api/user/info (needs a token in `token`) ----")
                    val account = runCatching { users.refresh() }
                    account.fold(
                        onSuccess = { appendLine("status=${it.status} msg=${it.msg}") },
                        onFailure = { appendLine("request failed: $it") },
                    )
                    val cached = users.current()
                    appendLine("cached userId=${cached.userId} mainUser=${cached.mainUser}")
                    appendLine("cached isVip=${users.current().isVip()} tokenStored=${users.token().isNotEmpty()}")
                    appendChannelReads(graph)
                    appendRemoteConfig(graph)
                    appendLocation(graph)
                }
            }.getOrElse { "FAILED: $it" }
        }
    }

    private fun themeResolved(): Boolean =
        obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground)).use { it.length() > 0 }

    /**
     * #17 read side, part 1: `utils.p` took the install channel out of **this APK's own signing
     * block**, so a device is the only place that can prove the reader. An unstamped build answers the
     * fallback id and carries no extras; a stamped one must answer the id it was stamped with.
     */
    private fun StringBuilder.appendChannelReads(graph: LinkUpApplication) {
        val channel = graph.installChannel
        appendLine("---- install channel (signing block section 0x71777777) ----")
        appendLine("apk=${applicationInfo.sourceDir}")
        appendLine("channel.id=${channel.id()}")
        appendLine("stamp extras=${(channel as? SigningBlockInstallChannel)?.extras()}")
        appendLine("addFriendTips=${graph.addFriendTips}")
        appendLine("1.6.6 rule askPermissionsAfterAdd=${graph.askPermissionsAfterAdd}")
    }

    /**
     * #17 read side, part 2: the config service turned out to be our own HTTP GET, so the probe asks it
     * once — deliberately, never from app startup — pushes the answer through the recovered store
     * writer and reads it back the way `ConfigUtils.j` did.
     */
    private suspend fun StringBuilder.appendRemoteConfig(graph: LinkUpApplication) {
        appendLine("---- remote config (GET configsys.51wnl-cq.com) ----")
        appendLine("url=${graph.configService.url()}")
        val before = StoredRemoteConfig(graph.keyValueStores)
        appendLine("throttle shouldFetch=${graph.configService.shouldFetch(before.storedAppVersion(), before.lastRequestAtMillis(), System.currentTimeMillis())}")
        val result = runCatching {
            withContext(Dispatchers.IO) { graph.keyValueStores.refreshRemoteConfig(graph.configService) }
        }
        result.fold(
            onSuccess = { appendLine("refreshRemoteConfig fetched=$it") },
            onFailure = { appendLine("refreshRemoteConfig FAILED: $it") },
        )
        val stored = StoredRemoteConfig(graph.keyValueStores)
        appendLine("token echo=${stored.storedLastUpdate()} storedAppVer=${stored.storedAppVersion()}")
        appendLine("stored ${PermissionChannelGate.KEY}=${stored.stringList(PermissionChannelGate.KEY)}")
        appendLine(
            "1.5.6 rule asksAfterAddFriendByConfig=" +
                PermissionChannelGate(graph.installChannel, stored).asksAfterAddFriendByConfig(),
        )
    }

    /**
     * #23 read side: the location layer is deliberately buildable with no vendor SDK, so a device is the
     * only place that can say which source it actually picked, what the thresholds really are on this
     * install, and whether the platform `Geocoder` answers at all (待验 66). Nothing here writes or reports
     * to the backend — it asks for one fix and one address, then stops.
     */
    private suspend fun StringBuilder.appendLocation(graph: LinkUpApplication) {
        appendLine("---- location layer (:location) ----")
        val tuning = graph.remoteConfig.locationTuning()
        appendLine(
            "tuning 休眠=${tuning.dormancyIntervalMillis}ms 节律=[${tuning.minIntervalSeconds}..${tuning.maxIntervalSeconds}]s " +
                "距离预算=${tuning.minUploadDistanceMeters}m",
        )
        appendLine(
            "cadence(0/1/2/8 m/s)=${tuning.cadenceSeconds(0f)},${tuning.cadenceSeconds(1f)}," +
                tuning.cadenceSeconds(2f) + ",${tuning.cadenceSeconds(8f)}",
        )
        val providers = locationProviders(this@RecoveryProbeActivity, privacyAgreed = { true })
        appendLine("candidates ${providers.describe()}")
        appendLine("permission=${LocationPermissions.of(this@RecoveryProbeActivity).state()}")
        val chosen = providers.preferred()
        if (chosen == null) {
            appendLine("没有可用定位来源")
            return
        }
        appendLine("chosen=${chosen.id} lastFix=${chosen.lastFix()}")
        val outcome = chosen.singleFix(LocationRequest(singleFixTimeoutMillis = 20_000))
        appendLine("singleFix=${outcome::class.simpleName} $outcome")
        val fix = (outcome as? LocationResult.Located)?.fix
        val resolver = SystemPlaceResolver(this@RecoveryProbeActivity)
        appendLine("PlaceResolver available=${resolver.isAvailable}")
        if (fix != null) {
            appendLine("wgs84=${fix.coord.lat},${fix.coord.lng} 精度=${fix.accuracyMeters}m 速度=${fix.speedMetersPerSecond}m/s")
            appendLine("resolvePlace=${resolver.resolvePlace(fix.coord)}")
            appendLine("bd09=${fix.coord.asBd09().lat},${fix.coord.asBd09().lng}（地图显示用的坐标系）")
        }
    }

    companion object {
        private const val LINKUP_HOST = "link.tttick.com"
    }
}
