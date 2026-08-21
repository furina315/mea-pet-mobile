package com.meapet.mobile.tts.g2p

/**
 * TTS 发音语言。本期开放中/日；英语仅占位（框架留接口，设置里不开放）。
 */
enum class TtsLanguage(val tag: String) {
    ZH("ZH"),
    JA("JA"),
    EN("EN");

    companion object {
        /** 从设置里存的字符串解析，容错回退中文。 */
        fun fromStored(value: String?): TtsLanguage =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ZH
    }
}

/**
 * 单语言 G2P：把一段文本转成 VITS 音素符号序列（不含语言标签、不含 blank）。
 *
 * 实现方负责把各自语言的文本转成 [SymbolTable] 内的符号序列；
 * 标签包裹与 add_blank 由 [G2pProcessor] 统一处理。
 */
interface LanguageG2p {
    /**
     * @param text 已去除语言标签的正文
     * @return 音素符号序列（每个元素应存在于 [SymbolTable]），表外符号自行降级/映射
     */
    fun phonemize(text: String): List<String>
}

/**
 * G2P 编排：按设置的默认语言分发到对应实现，包裹语言标签、转 ID、插 blank。
 *
 * 对应 `mea_vits_inference.py` 的 `_text_to_sequence`：
 *   text → cleaner(按语言) → 音素符号 → (标签包裹在符号阶段前) → ID → add_blank
 *
 * 注意：参考实现里 `[JA]...[JA]` 标签是在 **cleaner 之前**包到文本上的，
 * cleaner 会把标签转成对应语言处理。这里各 LanguageG2p 已实现语言特定的转换，
 * 故标签仅用于「多语言混排时给模型语言线索」——当前单语言模型直接按设置语言走，
 * 不再把标签文本混进音素序列（参考里标签本身也不进 68 符号表，cleaner 内部消化）。
 */
class G2pProcessor(
    private val chinese: LanguageG2p,
    private val japanese: LanguageG2p,
    private val english: LanguageG2p? = null   // 本期占位，可空
) {
    /**
     * 文本 → 音素 ID 序列（含 blank），可直接作为 VITS `x` 输入。
     *
     * @return 音素 ID 序列；文本无可合成内容时返回空数组
     */
    fun textToIds(text: String, lang: TtsLanguage): IntArray {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return IntArray(0)

        val g2p = when (lang) {
            TtsLanguage.ZH -> chinese
            TtsLanguage.JA -> japanese
            TtsLanguage.EN -> english ?: chinese   // 英语未开放时兜底中文（不会触发，骨架占位）
        }
        val symbols = g2p.phonemize(cleaned)
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
