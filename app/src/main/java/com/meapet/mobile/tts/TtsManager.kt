package com.meapet.mobile.tts

import android.util.Log
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.TtsLangProtocol
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.tts.audio.TtsAudioPlayer
import com.meapet.mobile.tts.g2p.TtsLanguage
import com.meapet.mobile.tts.model.TtsModelManager
import com.meapet.mobile.tts.model.TtsModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * TTS 门面：开关判断、分句、串行合成播放调度。
 *
 * ## 触发模型
 * 主界面与悬浮窗两路 LLM 回复都汇聚到这里：[speak] 先查对应开关与模型就绪，
 * 满足则把整段回复**分句**，串行合成——首句一出即播，后台继续合成后续句，
 * 流水线衔接降低长文本首音延迟。
 *
 * ## 打断
 * 新回复到达时 [speak] 会取消上一个合成 Job 并清空播放队列（旧话不念完）。
 *
 * 本期为骨架：语言取设置里的默认语音（中/日），日语译本块喂给 [speak] 的
 * 应是已选好的最终文本（中文正文或日语译本），本类不再做语言检测。
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

        /** 分句边界：中/日/英句读 + 换行。保留标点更利于模型断句韵律。 */
        private val SENTENCE_SPLIT = Regex("(?<=[。！？!?…；;])|\\n+")
    }

    /** 发声来源。 */
    enum class Source { MAIN, OVERLAY }

    @Volatile
    private var currentJob: Job? = null

    /**
     * 朗读一条助手回复。开关关闭或模型未就绪时静默跳过（不影响文字显示）。
     *
     * 朗读文本按默认语音选取：中文态读正文，日文态读 [ChatMessage.ttsJaBlock] 里的
     * 日语译本（无块则回退正文）。
     *
     * @param message 助手消息（content 已剥协议块；ttsJaBlock 存日语译本块原文）
     * @param source 触发来源（决定用哪个开关）
     */
    fun speak(message: ChatMessage, source: Source) {
        if (!isEnabledFor(source)) return
        if (modelManager.state.value != TtsModelState.Ready) {
            Log.d(TAG, "模型未就绪，跳过朗读")
            return
        }
        val lang = TtsLanguage.fromStored(settingsManager.getTtsLanguage())
        val text = resolveSpeakText(message, lang).trim()
        if (text.isEmpty()) return

        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return

        val lengthScale = settingsManager.getTtsLengthScale().toFloat()

        // 打断上一段：取消合成 + 清空播放队列
        currentJob?.cancel()
        player.stop()

        currentJob = scope.launch(Dispatchers.Default) {
            for ((index, sentence) in sentences.withIndex()) {
                try {
                    val audio = synthesizer.synthesize(
                        sentence, lang,
                        config = TtsSynthesizer.SynthesisConfig(lengthScale = lengthScale)
                    )
                    if (audio.isNotEmpty()) {
                        player.enqueue(audio)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "第 ${index + 1} 句合成失败: $sentence", e)
                }
            }
        }
    }

    /** 按默认语音取朗读文本：日文读译本块，否则读正文。 */
    private fun resolveSpeakText(message: ChatMessage, lang: TtsLanguage): String {
        if (lang != TtsLanguage.JA) return message.content
        val block = message.ttsJaBlock
        if (block.isNullOrBlank()) return message.content  // 无译本块回退正文
        return TtsLangProtocol.jaTextOfBlock(block) ?: message.content
    }

    /** 停止当前朗读并取消待合成任务。 */
    fun stop() {
        currentJob?.cancel()
        currentJob = null
        player.stop()
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

    /** 按句读符号分句，去除空白段。 */
    private fun splitSentences(text: String): List<String> =
        text.split(SENTENCE_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
