package com.youloft.watcher

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.youloft.linkup.net.LinkUpClient
import com.youloft.watcher.data.LinkUpApplication
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.data.local.PermissionChannelGate
import com.youloft.watcher.data.local.SigningBlockInstallChannel
import com.youloft.watcher.data.local.StoredRemoteConfig
import com.youloft.watcher.data.local.isVip
import com.youloft.watcher.data.local.refreshRemoteConfig
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

    companion object {
        private const val LINKUP_HOST = "link.tttick.com"
    }
}
