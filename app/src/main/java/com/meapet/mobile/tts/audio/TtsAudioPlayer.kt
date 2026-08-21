package com.meapet.mobile.tts.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.meapet.mobile.tts.TtsSynthesizer

/**
 * TTS 音频播放器：AudioTrack 流式播放 22050Hz 单声道 PCM16。
 *
 * 不复用 VoicePlayer 的 MediaPlayer——它面向 assets 里的完整 wav 文件，不适合
 * 合成出来的原始 PCM 流。这里直接把 fp32 波形转 int16 喂给 AudioTrack。
 *
 * 一段接一段地 [enqueue] 即可顺序播放（配合分句串行合成）；[stop] 清空并打断。
 * 所有方法线程安全；播放驱动在内部专用线程上。
 */
class TtsAudioPlayer {

    companion object {
        private const val TAG = "TtsAudioPlayer"
        const val SAMPLE_RATE = TtsSynthesizer.SAMPLE_RATE
    }

    /** 待播放的 PCM16 队列（已转好的 int16 样本）。 */
    private val queue = ArrayDeque<ShortArray>()
    private val lock = Object()

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var playerThread: Thread? = null

    /** 是否正在播放或队列非空（供 UI/调度判断忙碌态）。 */
    val isPlaying: Boolean
        get() = synchronized(lock) { queue.isNotEmpty() } || currentTrackPlaying()

    private fun currentTrackPlaying(): Boolean =
        track?.let { it.playState == AudioTrack.PLAYSTATE_PLAYING } == true

    /**
     * fp32 波形（[-1,1]）→ int16 并入队播放。对应参考 `audio * 32767` 转 int16。
     */
    fun enqueue(audio: FloatArray) {
        val pcm = ShortArray(audio.size) { i ->
            (audio[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        synchronized(lock) { queue.addLast(pcm) }
        ensureThread()
    }

    /** 清空队列并立即停止当前播放。 */
    fun stop() {
        synchronized(lock) { queue.clear() }
        track?.let {
            try {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause()
                it.flush()
            } catch (e: Exception) {
                Log.w(TAG, "stop 时清理 AudioTrack 异常", e)
            }
        }
    }

    /** 释放资源（停止并回收 AudioTrack）。 */
    fun release() {
        stop()
        track?.let {
            try {
                it.stop(); it.release()
            } catch (e: Exception) {
                Log.w(TAG, "release AudioTrack 异常", e)
            }
        }
        track = null
    }

    // ── 内部 ──────────────────────────────────────────

    private fun ensureThread() {
        if (playerThread?.isAlive == true) return
        playerThread = Thread({ drainLoop() }, "TtsAudioPlayer").also { it.start() }
    }

    /** 持续从队列取段播放，直到队列空。 */
    private fun drainLoop() {
        while (true) {
            val pcm = synchronized(lock) { queue.removeFirstOrNull() } ?: break
            try {
                playBlocking(pcm)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.e(TAG, "播放片段失败", e)
            }
        }
    }

    private fun playBlocking(pcm: ShortArray) {
        val t = obtainTrack(pcm.size)
        t.write(pcm, 0, pcm.size)
        // 队列里还有后续段：流式衔接，不阻塞等待当前段播完
        val hasMore = synchronized(lock) { queue.isNotEmpty() }
        if (!hasMore) {
            // 最后一段：阻塞等它真正放完，避免 stop 截断尾音
            val durationMs = pcm.size * 1000L / SAMPLE_RATE
            Thread.sleep(durationMs)
        }
    }

    private fun obtainTrack(minSamples: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, minSamples * 2)
        val existing = track
        if (existing != null && existing.bufferSizeInFrames * 2 >= bufSize) {
            if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) existing.play()
            return existing
        }
        existing?.let {
            try { it.stop(); it.release() } catch (_: Exception) {}
        }
        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        newTrack.play()
        track = newTrack
        return newTrack
    }
}
