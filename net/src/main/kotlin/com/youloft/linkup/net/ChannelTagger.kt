package com.youloft.linkup.net

import java.io.File

/**
 * CLI counterpart of [ApkSigningBlock.stamp]: writes the install-channel section into an APK, in the
 * place the recovered app reads it from (`0x71777777`, UTF-8 JSON with a `channel` key).
 *
 * It runs on an already-signed APK on purpose: the v2/v3 signed data covers only the platform's own
 * pairs, so this extra pair does not break the signature (checked with `apksigner verify` and
 * `unzip -t` against the built debug APK).
 *
 * Run: `gradle :net:tagChannel --args=<apk> <channel> [extraKey=value ...]`
 */
object ChannelTagger {

    @JvmStatic
    fun main(args: Array<String>) {
        val apk = args.getOrNull(0)?.let(::File) ?: error("usage: ChannelTagger <apk> <channel> [key=value ...]")
        val channel = args.getOrNull(1) ?: error("usage: ChannelTagger <apk> <channel> [key=value ...]")
        val extras = args.drop(2).mapNotNull { arg ->
            arg.substringBefore('=', "").takeIf { it.isNotEmpty() }?.let { it to arg.substringAfter('=') }
        }.toMap()

        val json = buildString {
            append('{')
            append("\"channel\":\"").append(channel).append('"')
            extras.forEach { (key, value) -> append(",\"").append(key).append("\":\"").append(value).append('"') }
            append('}')
        }.toByteArray(Charsets.UTF_8)

        val target = File(apk.parentFile, apk.name + ".stamped")
        ApkSigningBlock.stamp(apk, target, ApkSigningBlock.CHANNEL_SECTION_ID, json)
        target.copyTo(apk, overwrite = true)
        target.delete()

        val read = ApkSigningBlock.jsonSection(apk)
        check(read?.get(ApkSigningBlock.CHANNEL_KEY) == channel) { "stamped but could not read the channel back" }
        println("tagged ${apk.name}: channel=${read?.get(ApkSigningBlock.CHANNEL_KEY)} extras=${read?.minus(ApkSigningBlock.CHANNEL_KEY)}")
    }
}
