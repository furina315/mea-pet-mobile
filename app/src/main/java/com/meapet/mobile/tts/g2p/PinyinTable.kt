package com.meapet.mobile.tts.g2p

/**
 * 汉语拼音 → VITS 68 符号 IPA 映射表。
 *
 * 映射口径对齐 vits-fast-fine-tuning `text/mandarin.py` 的 `chinese_to_ipa`
 * （该模型训练用 `cjke_cleaners2`，中文段与其同源）。
 *
 * **68 符号表全是单字符条目**（`p`、`⁼`、`s`、`` ` ``、`ɹ`、`ʃ`…各自独立占一个 ID），
 * 因此本表产出的是**单字符序列**：如拼音 "ba" → [`p`,`⁼`,`a`,`→`]。
 * 设计要点（与标准 IPA 不同）：
 *
 * - 送气对立：不送气后接 `⁼`、送气后接 `ʰ`（b/p → `p⁼`/`pʰ`）。
 * - 卷舌后接反引号 `` ` ``：zh/ch/sh/r → `t s` `⁼` / `t s` `ʰ` / `s `` ` / `ɹ `` `。
 * - 舌面音 j/q/x 分解为 `t ʃ ⁼` / `t ʃ ʰ` / `ʃ`（68 表无合字 `ʧ`）。
 * - 舌尖元音（zi/ci/si 与 zhi/chi/shi/ri）补 `ɹ` / `ɹ `` `。
 * - 声调：阴平 `→`、阳平 `↑`、上声 `↓ ↑`、去声 `↓`、轻声不标。
 */
object PinyinTable {

    /** 声母 → 单字符音素序列。 */
    private val INITIALS: Map<String, List<String>> = mapOf(
        "b" to listOf("p", "⁼"),
        "p" to listOf("p", "ʰ"),
        "m" to listOf("m"),
        "f" to listOf("f"),
        "d" to listOf("t", "⁼"),
        "t" to listOf("t", "ʰ"),
        "n" to listOf("n"),
        "l" to listOf("l"),
        "g" to listOf("k", "⁼"),
        "k" to listOf("k", "ʰ"),
        "h" to listOf("x"),
        "j" to listOf("t", "ʃ", "⁼"),
        "q" to listOf("t", "ʃ", "ʰ"),
        "x" to listOf("ʃ"),
        "zh" to listOf("t", "s", "`", "⁼"),
        "ch" to listOf("t", "s", "`", "ʰ"),
        "sh" to listOf("s", "`"),
        "r" to listOf("ɹ", "`"),
        "z" to listOf("t", "s", "⁼"),
        "c" to listOf("t", "s", "ʰ"),
        "s" to listOf("s")
    )

    /** 主韵母 → 单字符音素序列（介音由 [finalToSymbols] 处理）。 */
    private val FINALS: Map<String, List<String>> = mapOf(
        "a" to listOf("a"),
        "o" to listOf("o"),
        "e" to listOf("ə"),
        "i" to listOf("i"),
        "u" to listOf("u"),
        "v" to listOf("ɥ"),                     // ü
        "ai" to listOf("a", "ɪ"),
        "ei" to listOf("e", "ɪ"),
        "ao" to listOf("ɑ", "ʊ"),
        "ou" to listOf("o", "ʊ"),
        "an" to listOf("a", "n"),
        "en" to listOf("ə", "n"),
        "ang" to listOf("ɑ", "ŋ"),
        "eng" to listOf("ə", "ŋ"),
        "ong" to listOf("ʊ", "ŋ"),
        "er" to listOf("ə", "ɹ"),
        "in" to listOf("i", "n"),
        "ing" to listOf("i", "ŋ"),
        "un" to listOf("u", "ə", "n"),
        "vn" to listOf("ɥ", "n")                // ün
    )

    /** 整体认读音节（零声母）→ 完整音素序列。y/w 起头的不再拆介音。 */
    private val WHOLE: Map<String, List<String>> = mapOf(
        "yi" to listOf("i"),
        "yin" to listOf("i", "n"),
        "ying" to listOf("i", "ŋ"),
        "wu" to listOf("u"),
        "yu" to listOf("ɥ"),
        "yun" to listOf("ɥ", "n"),
        "yuan" to listOf("ɥ", "ɛ", "n"),
        "yue" to listOf("ɥ", "ɛ"),
        "ya" to listOf("j", "a"),
        "yan" to listOf("j", "ɛ", "n"),
        "yang" to listOf("j", "ɑ", "ŋ"),
        "yao" to listOf("j", "ɑ", "ʊ"),
        "ye" to listOf("j", "ɛ"),
        "yo" to listOf("j", "o"),
        "yong" to listOf("j", "ʊ", "ŋ"),
        "you" to listOf("j", "o", "ʊ"),
        "wa" to listOf("w", "a"),
        "wai" to listOf("w", "a", "ɪ"),
        "wan" to listOf("w", "a", "n"),
        "wang" to listOf("w", "ɑ", "ŋ"),
        "wei" to listOf("w", "e", "ɪ"),
        "wen" to listOf("w", "ə", "n"),
        "weng" to listOf("w", "ə", "ŋ"),
        "wo" to listOf("w", "o"),
        "wong" to listOf("w", "ʊ", "ŋ")
    )

    /** 声调数字（1-5）→ 声调符号序列。轻声(5/0/无)不标。 */
    private val TONES = mapOf(
        1 to listOf("→"),
        2 to listOf("↑"),
        3 to listOf("↓", "↑"),
        4 to listOf("↓")
    )

    /** 纯鼻音/语气音节（无常规声韵母），如 嗯、呣。 */
    private val NASAL_ONLY: Map<String, List<String>> = mapOf(
        "ń" to listOf("n"), "ǹ" to listOf("n"), "ň" to listOf("n"),
        "ḿ" to listOf("m"), "m" to listOf("m"),
        "hm" to listOf("h", "m"), "hng" to listOf("h", "ŋ"),
        "n" to listOf("n"), "ng" to listOf("ŋ")
    )

    /**
     * 把一个带声调数字的拼音（如 "hao3"、"nv3"，ü 记作 v）映射为单字符音素序列。
     * 无法识别时返回空列表（调用方丢弃该字并记日志）。
     */
    fun pinyinToSymbols(pinyin: String): List<String> {
        if (pinyin.isEmpty()) return emptyList()
        val tone = pinyin.last().digitToIntOrNull()
        val base = if (tone != null) pinyin.dropLast(1).lowercase() else pinyin.lowercase()
        if (base.isEmpty()) return emptyList()

        val toneSyms = TONES[tone] ?: emptyList()

        // 纯鼻音/语气音节（嗯 ń/ǹ/ň、呣 ḿ、噷 hm 等），无常规声韵母，直接映射鼻音
        NASAL_ONLY[base]?.let { return it + toneSyms }

        // 整体认读（零声母 y/w/yu 系列）优先整体查表
        WHOLE[base]?.let { return it + toneSyms }

        val initialKey = listOf("zh", "ch", "sh").firstOrNull { base.startsWith(it) }
            ?: INITIALS.keys.firstOrNull { it.length == 1 && base.startsWith(it) }
        val initialSyms = initialKey?.let { INITIALS[it] } ?: emptyList()
        val rest = if (initialKey != null) base.removePrefix(initialKey) else base

        val finalSyms = finalToSymbols(rest, initialKey)
        return initialSyms + finalSyms + toneSyms
    }

    /** 韵母（含介音）→ 单字符音素。介音 i/u/ü 转半元音 j/w/ɥ；舌尖元音补 ɹ。 */
    private fun finalToSymbols(rest: String, initial: String?): List<String> {
        if (rest.isEmpty()) {
            // 舌尖元音：zi/ci/si 补 ɹ；zhi/chi/shi/ri 补 ɹ`（卷舌已在声母）
            return when (initial) {
                "z", "c", "s" -> listOf("ɹ")
                "zh", "ch", "sh", "r" -> listOf("ɹ", "`")
                else -> emptyList()
            }
        }
        if (rest.length > 1) {
            val medial = when (rest.first()) {
                'i' -> "j"
                'u' -> "w"
                'v' -> "ɥ"
                else -> null
            }
            if (medial != null) {
                FINALS[rest.substring(1)]?.let { return listOf(medial) + it }
            }
        }
        FINALS[rest]?.let { return it }
        return when (rest) {
            "i" -> listOf("i")
            "u" -> listOf("u")
            "v" -> listOf("ɥ")
            else -> emptyList()
        }
    }
}
