package com.meapet.mobile.tts.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.meapet.mobile.tts.TtsSynthesizer

/**
 * TTS 音频播放器：AudioTrack 播放 22050Hz 单声道 PCM16。
 *
 * 不复用 VoicePlayer 的 MediaPlayer——它面向 assets 里的完整 wav 文件，不适合
 * 合成出来的原始 PCM 流。这里直接把 fp32 波形转 int16 喂给 AudioTrack。
 *
 * 整段一次播放：[play] 起后台线程写完即播；[stop] 打断。线程安全。
 */
class TtsAudioPlayer {

    companion object {
        private const val TAG = "TtsAudioPlayer"
        const val SAMPLE_RATE = TtsSynthesizer.SAMPLE_RATE
    }

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var playThread: Thread? = null

    /**
     * fp32 波形（[-1,1]）→ int16 并播放。对应参考 `audio * 32767` 转 int16。
     * 新段到达会先停止旧段。
     */
    fun play(audio: FloatArray) {
        val pcm = ShortArray(audio.size) { i ->
            (audio[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        stop()
        playThread = Thread({ writePcm(pcm) }, "TtsAudioPlayer").also { it.start() }
    }

    /** 兼容旧调用（整段播放等价于入队即播）。 */
    fun enqueue(audio: FloatArray) = play(audio)

    /** 停止当前播放。 */
    fun stop() {
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
            t.write(pcm, 0, pcm.size)
        } catch (e: Exception) {
            Log.e(TAG, "播放失败", e)
        }
    }

    private fun obtainTrack(minSamples: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, minSamples * 2)
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
