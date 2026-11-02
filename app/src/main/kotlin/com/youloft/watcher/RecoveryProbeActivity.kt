package com.youloft.watcher

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.youloft.linkup.net.LinkUpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.EmptyCoroutineContext

/** Temporary entry point: proves the recovered signing stack works on a real device, not just on the JVM. */
class RecoveryProbeActivity : Activity() {

    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + EmptyCoroutineContext)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recovery_probe)
        val result = findViewById<TextView>(R.id.probe_result)
        result.text = "probing $LINKUP_HOST ..."

        val context = AndroidDeviceContext(this)
        ui.launch {
            result.text = runCatching {
                val response = LinkUpClient(context = context).api.clientConfig()
                buildString {
                    appendLine("status=${response.status} msg=${response.msg}")
                    appendLine("signature accepted by backend: ${response.isSuccess}")
                    response.data?.asJsonObject?.keySet()?.take(20)?.forEach { appendLine("  $it") }
                }
            }.getOrElse { "FAILED: $it" }
        }
    }

    companion object {
        private const val LINKUP_HOST = "link.tttick.com"
    }
}
