package com.meapet.mobile.tts.g2p

/**
 * VITS 音素符号表（68 个）。
 *
 * 与训练/导出口径严格一致，等价于模型包里的 `tokens.txt` / `lexicon.txt` /
 * `finetune_speaker.json` 的 `symbols` 字段。**顺序即 ID**，改动会直接喂错 ID 导致合成崩坏。
 *
 * 构成：blank `_`(0) → 标点(1-7) → 日语特殊音 `N Q`(8,9) → 26 小写字母(10-35) →
 * IPA 音素(36-54) → 送气 `⁼ ʰ`(55,56) → 韵律符号(57-66) → 空格(67)。
 */
object SymbolTable {

    /** 顺序即 ID，下标即音素 ID。 */
    val SYMBOLS: List<String> = listOf(
        "_",                                            // 0  blank
        ",", ".", "!", "?", "-", "~", "…",              // 1-7 标点
        "N", "Q",                                       // 8,9 日语拨音/促音
        "a", "b", "d", "e", "f", "g", "h", "i", "j", "k", // 10-19
        "l", "m", "n", "o", "p", "s", "t", "u", "v", "w", // 20-29
        "x", "y", "z",                                  // 30-32
        "ɑ", "æ", "ʃ", "ʑ", "ç", "ɯ", "ɪ", "ɔ", "ɛ", "ɹ", // 33-42 IPA
        "ð", "ə", "ɫ", "ɥ", "ɸ", "ʊ", "ɾ", "ʒ", "θ", "β", // 43-52 IPA
        "ŋ", "ɦ",                                       // 53,54 IPA
        "⁼", "ʰ",                                       // 55,56 送气
        "`", "^", "#", "*", "=", "ˈ", "ˌ", "→", "↓", "↑", // 57-66 韵律
        " "                                             // 67 空格
    )

    val SIZE: Int = SYMBOLS.size

    /** blank（静音/间隔）ID，恒为 0。 */
    const val BLANK_ID: Int = 0

    private val symbolToId: Map<String, Int> = SYMBOLS.withIndex().associate { it.value to it.index }

    /** 单符号 → ID；表外符号返回 null（调用方决定降级策略）。 */
    fun idOf(symbol: String): Int? = symbolToId[symbol]

    /** 是否表内符号。 */
    fun contains(symbol: String): Boolean = symbolToId.containsKey(symbol)

    /**
     * 把音素符号序列转成 ID 序列；表外符号静默丢弃并计数返回。
     *
     * @return Pair(ID 序列, 被丢弃的表外符号列表)——丢弃列表用于排查映射缺失
     */
    fun toIds(symbols: List<String>): Pair<IntArray, List<String>> {
        val ids = ArrayList<Int>(symbols.size)
        val dropped = ArrayList<String>()
        for (s in symbols) {
            val id = symbolToId[s]
            if (id != null) ids.add(id) else dropped.add(s)
        }
        return ids.toIntArray() to dropped
    }

    /**
     * add_blank：在序列首尾及每两个符号间插入 blank。
     *
     * 训练配置 `add_blank=true`，输入序列形如 `[0, s0, 0, s1, 0, ...]`，
     * 长度 = 2×原长 + 1。对应 `mea_vits_inference.py` 的 `_text_to_sequence`。
     */
    fun insertBlank(ids: IntArray): IntArray {
        val out = IntArray(ids.size * 2 + 1)
        out[0] = BLANK_ID
        var i = 0
        for (id in ids) {
            out[i * 2 + 1] = id
            out[i * 2 + 2] = BLANK_ID
            i++
        }
        return out
    }
}
