package com.meapet.mobile.tts.g2p

import android.content.Context

/**
 * 中文 G2P：汉字 → 拼音（多音字定音）→ IPA 音素符号。
 *
 * ## 链路
 * 1. 标点/空白 → 对应韵律符号（，。！？… 等直接映射进 68 符号表）；
 * 2. 多音词优先按 [PinyinDict.POLYPHONE_WORDS] 定音，其余汉字逐字查 [PinyinDict]；
 * 3. 拼音 → 声母/韵母 → [PinyinTable] 映射到 68 符号 IPA。
 */
class ChineseG2p(context: Context) : LanguageG2p {

    init {
        // 加载全量拼音表（assets/pinyin/zh_pinyin.txt）
        PinyinDict.init(context.applicationContext)
    }

    override fun phonemize(text: String): List<String> {
        val out = ArrayList<String>()
        val chars = text.toList()
        var i = 0
        while (i < chars.size) {
            val ch = chars[i]
            when {
                ch.isWhitespace() -> out.add(" ")
                ch in PUNCT -> out.add(PUNCT.getValue(ch))
                isCjk(ch) -> {
                    // 多音词优先：尝试匹配以当前字开头的多音词
                    val word = matchPolyphone(chars, i)
                    if (word != null) {
                        word.forEach { out.addAll(PinyinTable.pinyinToSymbols(it)) }
                        i += word.size - 1   // 跳过该词剩余字（循环末尾再 +1）
                    } else {
                        PinyinDict.lookup(ch)?.let { out.addAll(PinyinTable.pinyinToSymbols(it)) }
                        // 未收录汉字：跳过（不产出表外符号）
                    }
                }
                // 半角字母/数字等暂不入模型（中文模型对拉丁字母读音弱），跳过
                else -> Unit
            }
            i++
        }
        return out
    }

    /** 从位置 [start] 起匹配最长的多音词，命中返回其拼音序列，否则 null。 */
    private fun matchPolyphone(chars: List<Char>, start: Int): List<String>? {
        for (len in 4 downTo 2) {
            if (start + len > chars.size) continue
            val candidate = chars.subList(start, start + len).joinToString("")
            PinyinDict.POLYPHONE_WORDS[candidate]?.let { return it }
        }
        return null
    }

    private fun isCjk(c: Char): Boolean =
        c in '一'..'鿿' || c in '㐀'..'䶿'

    companion object {
        /** 中文标点 → 符号表内标点。 */
        private val PUNCT = mapOf(
            '，' to ",", '。' to ".", '！' to "!", '？' to "?",
            '、' to ",", '…' to "…", '：' to ",", '；' to ",",
            ',' to ",", '.' to ".", '!' to "!", '?' to "?",
            '-' to "-", '~' to "~"
        )
    }
}
