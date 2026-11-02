package com.youloft.watcher

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.youloft.linkup.net.LinkUpClient
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
