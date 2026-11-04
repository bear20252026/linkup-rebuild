package com.youloft.watcher

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.youloft.linkup.net.LinkUpClient
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.data.local.isVip
import kotlinx.coroutines.launch

/** Entry point: proves the recovered signing stack and the recovered theme run on Android. */
class RecoveryProbeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recovery_probe)
        val result = findViewById<TextView>(R.id.probe_result)
        result.text = "probing $LINKUP_HOST ..."

        val context = AndroidDeviceContext(this)
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
                    val users = appGraph(this@RecoveryProbeActivity).users
                    appendLine("---- GET api/user/info (needs a token in `token`) ----")
                    val account = runCatching { users.refresh() }
                    account.fold(
                        onSuccess = { appendLine("status=${it.status} msg=${it.msg}") },
                        onFailure = { appendLine("request failed: $it") },
                    )
                    val cached = users.current()
                    appendLine("cached userId=${cached.userId} mainUser=${cached.mainUser}")
                    appendLine("cached isVip=${users.current().isVip()} tokenStored=${users.token().isNotEmpty()}")
                }
            }.getOrElse { "FAILED: $it" }
        }
    }

    private fun themeResolved(): Boolean =
        obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground)).use { it.length() > 0 }

    companion object {
        private const val LINKUP_HOST = "link.tttick.com"
    }
}
