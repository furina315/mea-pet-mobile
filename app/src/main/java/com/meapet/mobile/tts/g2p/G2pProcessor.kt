package com.meapet.mobile.tts.g2p

/**
 * TTS 发音语言。本期仅开放中文；枚举保留以便后续扩展。
 */
enum class TtsLanguage(val tag: String) {
    ZH("ZH");

    companion object {
        /** 从设置里存的字符串解析，容错回退中文。 */
        fun fromStored(value: String?): TtsLanguage =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ZH
    }
}

/**
 * 单语言 G2P：把一段文本转成 VITS 音素符号序列（不含语言标签、不含 blank）。
 */
interface LanguageG2p {
    /**
     * @param text 待转换正文
     * @return 音素符号序列（每个元素应存在于 [SymbolTable]），表外符号自行降级/映射
     */
    fun phonemize(text: String): List<String>
}

/**
 * G2P 编排：中文文本 → 音素 ID 序列（含 blank），可直接作为 VITS `x` 输入。
 *
 * 对应 `mea_vits_inference.py` 的 `_text_to_sequence`：cleaner → 音素符号 → ID → add_blank。
 * 当前仅支持中文（单语言模型按 [TtsLanguage] 走对应实现）。
 */
class G2pProcessor(
    private val chinese: LanguageG2p
) {
    /**
     * 文本 → 音素 ID 序列（含 blank）。
     * @return 音素 ID 序列；文本无可合成内容时返回空数组
     */
    fun textToIds(text: String, lang: TtsLanguage): IntArray {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return IntArray(0)

        val symbols = chinese.phonemize(cleaned)
        if (symbols.isEmpty()) return IntArray(0)

        val (ids, dropped) = SymbolTable.toIds(symbols)
        if (dropped.isNotEmpty()) {
            android.util.Log.w(TAG, "G2P 产生 ${dropped.size} 个表外符号被丢弃: ${dropped.distinct()}")
        }
        if (ids.isEmpty()) return IntArray(0)
        return SymbolTable.insertBlank(ids)
    }

    private companion object {
        const val TAG = "G2pProcessor"
    }
}
