package com.meapet.mobile.tts

import android.util.Log
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.tts.audio.TtsAudioPlayer
import com.meapet.mobile.tts.g2p.TtsLanguage
import com.meapet.mobile.tts.model.TtsModelManager
import com.meapet.mobile.tts.model.TtsModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * TTS 门面：开关判断 + 整段合成播放。
 *
 * 主界面与悬浮窗两路 LLM 回复都汇聚到这里：[speak] 先查对应开关与模型就绪，
 * 满足则把整段回复一次性合成后播放。新回复到达时取消上一段（旧话不念完）。
 *
 * 当前仅支持中文，整段一次合成（不分句、不并行），保持实现简单可靠。
 */
class TtsManager(
    private val settingsManager: SettingsManager,
    private val modelManager: TtsModelManager,
    private val synthesizer: TtsSynthesizer,
    private val player: TtsAudioPlayer,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TtsManager"

        /**
         * TTS 开始播放时的回调（互斥：停掉未完的触摸语音）。
         * 由 app 容器注入，避免 tts 包反向依赖 live2d。
         */
        @Volatile
        var onPlaybackStart: (() -> Unit)? = null
    }

    /** 发声来源。 */
    enum class Source { MAIN, OVERLAY }

    /** 是否正在合成或播放（驱动喇叭图标变色）。 */
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** 本次会话（内存）语音是否被临时关闭；不持久化，重启 App 即恢复。 */
    private val _sessionMuted = MutableStateFlow(false)
    val sessionMuted: StateFlow<Boolean> = _sessionMuted.asStateFlow()

    init {
        // 真实播放完成（硬件播放头到末尾帧）→ 解除播放中标记
        player.onPlaybackComplete = { _isPlaying.value = false }
    }

    /** 切换本次会话的语音开关（快捷静音按钮用）。关闭时同时停止当前朗读。 */
    fun toggleSessionMute() {
        val muted = !_sessionMuted.value
        _sessionMuted.value = muted
        if (muted) stop()
    }

    @Volatile
    private var currentJob: Job? = null

    /**
     * 朗读一条助手回复。开关关闭 / 会话静音 / 模型未就绪时静默跳过（不影响文字显示）。
     *
     * @param message 助手消息（content 已剥协议块）
     * @param source 触发来源（决定用哪个开关）
     */
    fun speak(message: ChatMessage, source: Source) {
        if (!isEnabledFor(source)) return
        if (_sessionMuted.value) {
            Log.d(TAG, "本次会话已静音，跳过朗读")
            return
        }
        if (modelManager.state.value != TtsModelState.Ready) {
            Log.d(TAG, "模型未就绪，跳过朗读")
            return
        }
        val text = message.content.trim()
        if (text.isEmpty()) return

        val lengthScale = settingsManager.getTtsLengthScale().toFloat()

        // 打断上一段：取消合成 + 停止播放
        currentJob?.cancel()
        player.stop()

        currentJob = scope.launch(Dispatchers.Default) {
            _isPlaying.value = true
            try {
                val audio = synthesizer.synthesize(
                    text, TtsLanguage.ZH,
                    config = TtsSynthesizer.SynthesisConfig(lengthScale = lengthScale)
                )
                if (audio.isNotEmpty()) {
                    onPlaybackStart?.invoke()   // 互斥：停掉未完的触摸语音
                    player.play(audio)          // 播完经 onPlaybackComplete 解除 isPlaying
                } else {
                    _isPlaying.value = false
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "合成失败: $text", e)
                _isPlaying.value = false
            }
        }
    }

    /** 停止当前朗读并取消待合成任务。 */
    fun stop() {
        currentJob?.cancel()
        currentJob = null
        player.stop()
        _isPlaying.value = false
    }

    /** 释放播放器与推理资源。 */
    fun release() {
        stop()
        player.release()
    }

    /** 对应来源的语音开关是否开启。 */
    private fun isEnabledFor(source: Source): Boolean = when (source) {
        Source.MAIN -> settingsManager.isTtsMainEnabled()
        Source.OVERLAY -> settingsManager.isTtsOverlayEnabled()
    }
}
