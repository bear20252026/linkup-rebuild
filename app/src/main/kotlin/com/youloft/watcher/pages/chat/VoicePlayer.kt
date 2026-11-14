package com.youloft.watcher.pages.chat

import android.media.MediaPlayer

/**
 * 语音消息的播放 seam：真实实现是 MediaPlayer 流播（MinIO URL），测试里可换成记录用的替身。
 * 一次只有一条语音在响——[start] 会先停掉上一条；[onChanged] 在起播/播完/停止时回调，让列表刷新
 * 播放中的那一行。
 */
interface VoicePlayer {
    /** 当前正在播的 URL；null 表示静默。 */
    val playingUrl: String?

    /** 播一条；已经在播的同一条上再点一次即停止（返回 false）。 */
    fun start(url: String): Boolean

    fun stop()

    fun release()
}

class MediaPlayerVoicePlayer(private val onChanged: () -> Unit) : VoicePlayer {

    private var player: MediaPlayer? = null
    private var current: String? = null

    override val playingUrl: String? get() = current

    override fun start(url: String): Boolean {
        if (current == url) {
            stop()
            return false
        }
        stop()
        return runCatching {
            val created = MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { it.start() }
                setOnCompletionListener {
                    current = null
                    onChanged()
                }
                setOnErrorListener { _, _, _ ->
                    current = null
                    onChanged()
                    true
                }
                prepareAsync()
            }
            player = created
            current = url
            onChanged()
            true
        }.getOrDefault(false)
    }

    override fun stop() {
        runCatching { player?.stop() }
        player?.release()
        player = null
        val changed = current != null
        current = null
        if (changed) onChanged()
    }

    override fun release() {
        stop()
        runCatching { player?.release() }
        player = null
    }
}
