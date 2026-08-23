package com.meapet.mobile.tts

import com.meapet.mobile.tts.g2p.G2pProcessor
import com.meapet.mobile.tts.g2p.TtsLanguage

/**
 * VITS 推理编排：G2P → enc_p → dp → 时长展开/先验展开/采样 → flow → dec → 波形。
 *
 * 对应 `mea_vits_inference.py` 的 `synthesize()`，单句一次性合成，返回 22050Hz fp32 波形。
 * 分句/流式调度在 [TtsManager]，本类只负责「一段文本 → 一段音频」。
 */
class TtsSynthesizer(
    private val engine: VitsOnnxEngine,
    private val g2p: G2pProcessor,
    private val config: SynthesisConfig = SynthesisConfig()
) {
    companion object {
        const val SAMPLE_RATE = 22050
    }

    data class SynthesisConfig(
        val noiseScale: Float = 0.667f,    // 音高随机性，越大越有感情
        val noiseScaleW: Float = 0.8f,     // 时长随机性，越大节奏越自然
        val lengthScale: Float = 1.0f      // 语速：<1 变快，>1 变慢
    )

    /**
     * 单句合成。
     * @param config 当句的合成参数（默认语速等可被设置覆盖）；不传用默认
     * @return 22050Hz fp32 波形；文本无有效内容时返回空数组
     */
    fun synthesize(
        text: String,
        lang: TtsLanguage,
        config: SynthesisConfig = this.config
    ): FloatArray {
        val ids = g2p.textToIds(text, lang)
        if (ids.isEmpty()) return FloatArray(0)

        val enc = engine.runEnc(ids.map { it.toLong() }.toLongArray())
        val logw = engine.runDp(enc.xEnc, enc.xMask, config.noiseScaleW)

        val (attn, yLengths) = DurationExpander.buildAttention(
            logw = logw,
            xMask = enc.xMask,
            lengthScale = config.lengthScale
        )
        val mPExp = DurationExpander.expandPrior(attn, enc.mP)
        val logsPExp = DurationExpander.expandPrior(attn, enc.logsP)
        val zP = DurationExpander.sampleZp(mPExp, logsPExp, config.noiseScale)

        val z = engine.runFlow(zP, yLengths)
        return engine.runDec(z, yLengths)
    }

    /** 释放底层 ONNX session 与 native 内存（删模型时调用，防止 73MB+ 泄漏）。 */
    fun closeEngine() = engine.close()
}
