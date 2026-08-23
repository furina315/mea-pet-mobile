package com.meapet.mobile.live2d.audio

import android.content.Context
import android.media.MediaPlayer
import android.util.Log

/**
 * 语音播放器。
 * 从 assets/voice/ 加载并播放 .wav 文件。
 *
 * 由 GL 线程调用。[play] 使用 [MediaPlayer.prepareAsync] 异步准备，
 * 避免在 GL 线程上同步阻塞（同步 prepare 会拖住 GL 帧，进而经
 * GLSurfaceView.onPause 阻塞主线程导致 ANR）。
 *
 * 线程安全：[player] 的所有读写都经 [lock] 同步，可在 GL 线程（触摸触发）
 * 与协程线程（TTS 互斥 stopTouchVoices）间安全调用。
 *
 * @param voiceDir 语音子目录，如 "voice/upper"、"voice/lower_left"、"voice/lower_right"
 */
class VoicePlayer(
    private val context: Context,
    private val voiceDir: String
) {
    private val lock = Any()
    private var player: MediaPlayer? = null

    companion object {
        private const val TAG = "VoicePlayer"
    }

    /** 列出该子目录下所有 .wav 文件。 */
    fun listVoices(): List<String> {
        return try {
            context.assets.list(voiceDir)
                ?.filter { it.endsWith(".wav") }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun play(filename: String) {
        // fd 必须及时关闭：setDataSource 读取完成后 fd 不再被持有，开着会随播放次数累积泄漏
        context.assets.openFd("$voiceDir/$filename").use { afd ->
            try {
                // 先停掉上一个（含正在异步准备的）
                stop()
                val mp = MediaPlayer().apply {
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    setOnCompletionListener { stop() }
                    setOnPreparedListener { p ->
                        // 准备完成才起播；若期间已被 stop（p 已不在锁内）则忽略
                        synchronized(lock) {
                            if (player === p) {
                                try { p.start() } catch (e: Exception) { Log.w(TAG, "start 失败", e) }
                            }
                        }
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.w(TAG, "MediaPlayer error: $what / $extra")
                        stop()
                        true
                    }
                }
                synchronized(lock) { player = mp }
                // 异步准备，立即返回不阻塞调用线程
                mp.prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play $filename", e)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
    }
}
