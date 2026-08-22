package com.meapet.mobile.tts.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meapet.mobile.tts.TtsSynthesizer

/**
 * TTS 音频播放器：AudioTrack 播放 22050Hz 单声道 PCM16。
 *
 * 整段一次播放：[play] 起后台线程写完即播；[stop] 打断。线程安全。
 *
 * ## 播放完成判断（真实进度，非 playState 估算）
 * MODE_STREAM 下 `playState==PLAYING` 只表示"处于播放态"，缓冲写完后仍一直 PLAYING，
 * 不能用来判断播完。这里用 [AudioTrack.setPlaybackPositionUpdateListener] 注册
 * **末尾帧位置标记**，硬件播放头推进到该帧才回调 [onPlaybackComplete]——这是真实进度。
 */
class TtsAudioPlayer {

    companion object {
        private const val TAG = "TtsAudioPlayer"
        const val SAMPLE_RATE = TtsSynthesizer.SAMPLE_RATE
    }

    /** 播放完成回调（硬件播放头到达末尾帧时触发，主线程）。 */
    @Volatile
    var onPlaybackComplete: (() -> Unit)? = null

    /** 是否正在播放（有未完成片段）。供互斥判断。 */
    @Volatile
    var isPlaying: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var playThread: Thread? = null

    /** 当前片段总帧数（用于位置标记）。 */
    @Volatile
    private var totalFrames = 0

    /**
     * fp32 波形（[-1,1]）→ int16 并播放。对应参考 `audio * 32767` 转 int16。
     * 新段到达会先停止旧段。
     */
    fun play(audio: FloatArray) {
        val pcm = ShortArray(audio.size) { i ->
            (audio[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        stop()
        isPlaying = true
        playThread = Thread({ writePcm(pcm) }, "TtsAudioPlayer").also { it.start() }
    }

    /** 兼容旧调用（整段播放等价于入队即播）。 */
    fun enqueue(audio: FloatArray) = play(audio)

    /** 停止当前播放。 */
    fun stop() {
        isPlaying = false
        track?.let {
            try {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause()
                it.flush()
            } catch (e: Exception) {
                Log.w(TAG, "stop 清理 AudioTrack 异常", e)
            }
        }
    }

    /** 释放资源。 */
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

    private fun writePcm(pcm: ShortArray) {
        try {
            val t = obtainTrack(pcm.size)
            // 注册末尾帧标记：硬件播到最后一帧时回调（真实播放完成）
            t.setPositionNotificationPeriod(pcm.size)   // 周期回调（兜底）
            t.notificationMarkerPosition = pcm.size     // 末尾帧标记
            t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) {
                    isPlaying = false
                    mainHandler.post { onPlaybackComplete?.invoke() }
                }
                override fun onPeriodicNotification(t: AudioTrack?) {
                    // 周期回调：核对播放头是否已到末尾（兜底，stop 后不再触发）
                    val head = t?.playbackHeadPosition ?: 0
                    if (totalFrames > 0 && head >= totalFrames && isPlaying) {
                        isPlaying = false
                        mainHandler.post { onPlaybackComplete?.invoke() }
                    }
                }
            }, mainHandler)
            t.write(pcm, 0, pcm.size)
        } catch (e: Exception) {
            Log.e(TAG, "播放失败", e)
            isPlaying = false
        }
    }

    private fun obtainTrack(minSamples: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, minSamples * 2)
        totalFrames = minSamples
        track?.let {
            if (it.bufferSizeInFrames * 2 >= bufSize) {
                if (it.playState != AudioTrack.PLAYSTATE_PLAYING) it.play()
                return it
            }
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
