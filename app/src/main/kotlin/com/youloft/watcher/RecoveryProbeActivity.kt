package com.youloft.watcher

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.youloft.linkup.net.LinkUpClient
import kotlinx.coroutines.launch

/** Temporary entry point: proves the recovered signing stack works on a real device, not just on the JVM. */
class RecoveryProbeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recovery_probe)
        val result = findViewById<TextView>(R.id.probe_result)
        result.text = "probing $LINKUP_HOST ..."

        lifecycleScope.launch {
            result.text = runCatching {
                val api = LinkUpClient(context = AndroidDeviceContext(this@RecoveryProbeActivity)).api
                val response = api.clientConfig()
                buildString {
                    appendLine("status=${response.status} msg=${response.msg}")
                    appendLine("signature accepted by backend: ${response.isSuccess}")
                    response.data?.asJsonObject?.keySet()?.take(20)?.forEach { appendLine("  $it") }
                }
            }.getOrElse { "FAILED: $it" }
        }
    }

    companion object {
        private const val LINKUP_HOST = "linkup-test.51wnl-cq.com"
    }
}
