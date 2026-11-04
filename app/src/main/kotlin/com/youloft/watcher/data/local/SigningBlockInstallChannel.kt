package com.youloft.watcher.data.local

import android.content.Context
import com.youloft.linkup.net.ApkSigningBlock
import java.io.File

/**
 * The install channel, read the way the recovered app reads it: `utils.p` asks `hc.i` for the
 * `channel` key of the `0x71777777` section of **its own installed APK**, and falls back to a literal
 * when that section is absent — which is the normal case for our builds until the packaging step
 * writes one (`:net:tagChannel`).
 */
class SigningBlockInstallChannel(
    apk: File?,
    private val fallback: String = FallbackInstallChannel.DEFAULT,
) : InstallChannel {

    private val sections: Map<String, String>? by lazy {
        apk?.takeIf { it.isFile }?.let { file -> runCatching { ApkSigningBlock.jsonSection(file) }.getOrNull() }
    }

    override fun id(): String = sections?.get(ApkSigningBlock.CHANNEL_KEY)?.takeIf { it.isNotEmpty() } ?: fallback

    /** Whatever else the section carries besides `channel` — the recovered `hc.c.b()`. */
    fun extras(): Map<String, String> = (sections ?: emptyMap()).minus(ApkSigningBlock.CHANNEL_KEY)

    companion object {
        fun from(context: Context): SigningBlockInstallChannel =
            SigningBlockInstallChannel(context.applicationInfo?.sourceDir?.takeIf { it.isNotEmpty() }?.let(::File))
    }
}
