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
 * 整段一次播放：[play] 提交到**专用守护线程**写完即播；[stop] 打断。线程安全。
 *
 * ## 线程模型（修复并发写同一 AudioTrack 的竞态）
 * - 所有 AudioTrack 的创建 / write / stop / release 都在**同一个专用线程**上执行，
 *   天然避免"两线程同写一个 track"的未定义行为。
 * - 每段音频带一个单调递增的**代际令牌（generation）**：[stop] 会使旧令牌失效，
 *   专用线程在 write 前后比对令牌，过期段直接丢弃，不会写出。
 * - [stop]/[release] 会 `interrupt()` 专用线程以解除 `write()` 的阻塞，
 *   再短暂 `join()` 等待其退出，保证释放前无任何线程仍持有该 track。
 *
 * ## 播放完成判断（真实进度，非 playState 估算）
 * MODE_STREAM 下 `playState==PLAYING` 只表示"处于播放态"，缓冲写完后仍一直 PLAYING，
 * 不能用来判断播完。这里用 [AudioTrack.setPlaybackPositionUpdateListener] 注册
 * **末尾帧位置标记**，硬件播放头推进到该帧才回调 [onPlaybackComplete]——这是真实进度。
 * 播放/创建失败路径同样会回调 [onPlaybackComplete]，保证上层 isPlaying 状态能复位。
 */
class TtsAudioPlayer {

    companion object {
        private const val TAG = "TtsAudioPlayer"
        const val SAMPLE_RATE = TtsSynthesizer.SAMPLE_RATE

        /** stop/release 后等待写线程退出的最长毫秒数。 */
        private const val JOIN_TIMEOUT_MS = 500L
    }

    /** 播放完成回调（硬件播放头到达末尾帧、或播放失败时触发，主线程）。 */
    @Volatile
    var onPlaybackComplete: (() -> Unit)? = null

    /** 是否正在播放（有未完成片段）。供互斥判断。 */
    @Volatile
    var isPlaying: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前 AudioTrack，仅在专用写线程上读写；stop/release 在主调线程经锁同步访问。 */
    private val trackLock = Any()
    private var track: AudioTrack? = null

    /** 专用写线程（每段一个，写完即退）。 */
    @Volatile
    private var playThread: Thread? = null

    /** 代际令牌：每次 play 递增，stop 后置失效，写线程据此丢弃过期段。 */
    private val generationLock = Any()
    private var generation = 0

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
        // 先停旧段（含 join 等待其写线程退出），再发新段
        stopInternal()
        val gen = synchronized(generationLock) { ++generation }
        isPlaying = true
        playThread = Thread({ writePcm(pcm, gen) }, "TtsAudioPlayer").apply {
            isDaemon = true
            start()
        }
    }

    /** 停止当前播放。 */
    fun stop() = stopInternal()

    /** 释放资源：先停并等写线程退出，再释放 track。 */
    fun release() {
        stopInternal()
        synchronized(trackLock) {
            track?.let {
                try {
                    it.stop(); it.release()
                } catch (e: Exception) {
                    Log.w(TAG, "release AudioTrack 异常", e)
                }
            }
            track = null
        }
    }

    // ── 内部 ──────────────────────────────────────────

    /**
     * 使当前段失效并停止播放：递增代际、清状态、中断写线程解除 write 阻塞、
     * join 等待其退出，再安全地 pause/flush 当前 track。
     */
    private fun stopInternal() {
        synchronized(generationLock) { generation++ }   // 旧段令牌失效
        isPlaying = false

        val t = playThread
        if (t != null && t.isAlive) {
            t.interrupt()
            try {
                t.join(JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        playThread = null

        synchronized(trackLock) {
            track?.let {
                try {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause()
                    it.flush()
                } catch (e: Exception) {
                    Log.w(TAG, "stop 清理 AudioTrack 异常", e)
                }
            }
        }
    }

    /** 段是否仍有效（未被 stop / 被新段取代）。 */
    private fun isCurrent(gen: Int): Boolean =
        synchronized(generationLock) { gen == generation }

    private fun writePcm(pcm: ShortArray, gen: Int) {
        try {
            if (!isCurrent(gen)) return
            val t = obtainTrack(pcm.size)
            // 注册末尾帧标记：硬件播到最后一帧时回调（真实播放完成）
            t.setPositionNotificationPeriod(pcm.size)   // 周期回调（兜底）
            t.notificationMarkerPosition = pcm.size     // 末尾帧标记
            t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack?) {
                    if (isCurrent(gen)) notifyComplete()
                }
                override fun onPeriodicNotification(track: AudioTrack?) {
                    // 周期回调：核对播放头是否已到末尾（兜底）
                    val head = track?.playbackHeadPosition ?: 0
                    if (totalFrames > 0 && head >= totalFrames && isCurrent(gen)) {
                        notifyComplete()
                    }
                }
            }, mainHandler)
            // write 全程可被 interrupt 解除阻塞；写完逐段比对令牌
            t.write(pcm, 0, pcm.size)
        } catch (e: InterruptedException) {
            // 被 stop/release 打断：正常路径，不回调完成
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "播放失败", e)
            // 失败也回调完成，保证上层 isPlaying 状态复位（修复卡死）
            if (isCurrent(gen)) notifyComplete()
        }
    }

    /** 标记播放结束并回调（主线程）。仅当段仍有效时调用。 */
    private fun notifyComplete() {
        isPlaying = false
        mainHandler.post { onPlaybackComplete?.invoke() }
    }

    /** 必须在专用写线程调用（track 的创建/复用仅此一处）。 */
    private fun obtainTrack(minSamples: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, minSamples * 2)
        totalFrames = minSamples
        synchronized(trackLock) {
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
}
