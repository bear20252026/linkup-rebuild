package com.youloft.watcher.pages.chat

import android.media.MediaRecorder
import java.io.File

/**
 * 语音消息的录音器：MediaRecorder AAC→MPEG4，落 [outputDir] 的 `voice-<时间戳>.m4a`。
 * [start] 在权限已授予的前提下调用；[stop] 交出 (路径, 秒)——录音太短时系统 stop 会抛，
 * 那是"没录上"的诚实信号，返回 null 让调用方提示；[cancel] 丢弃文件。
 * 真机行为（采样率/音量/各 ROM 差异）是待验项，见 DESIGN 五点三十八。
 */
class VoiceRecorder(private val outputDir: File) {

    private var recorder: MediaRecorder? = null
    private var outputPath: String? = null
    private var startedAtMs: Long = 0

    /** 开始录音；返回 false 表示底层没能进入录音态（权限/占用）。 */
    fun start(): Boolean {
        if (recorder != null) return false
        outputDir.mkdirs()
        val file = File(outputDir, "voice-${System.currentTimeMillis()}.m4a")
        val created = runCatching {
            MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        }.getOrNull() ?: return false
        recorder = created
        outputPath = file.absolutePath
        startedAtMs = System.currentTimeMillis()
        return true
    }

    /** 停止并交出 (路径, 秒)；时长不足 1 秒或底层拒绝结束 → null。 */
    fun stop(): Pair<String, Long>? {
        val path = outputPath ?: return null
        val stopped = runCatching { recorder?.stop() }.isSuccess
        release()
        if (!stopped) {
            File(path).delete()
            return null
        }
        val seconds = ((System.currentTimeMillis() - startedAtMs + 999) / 1000).coerceAtLeast(1)
        return path to seconds
    }

    /** 丢弃本次录音（手势划出/取消）。 */
    fun cancel() {
        outputPath?.let { runCatching { File(it).delete() } }
        release()
    }

    private fun release() {
        recorder?.release()
        recorder = null
        outputPath = null
        startedAtMs = 0
    }
}
