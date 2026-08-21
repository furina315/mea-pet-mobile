package com.meapet.mobile.tts.g2p

import android.content.Context

/**
 * 中文 G2P：汉字 → 拼音（多音字定音）→ IPA 音素符号。
 *
 * ## 链路
 * 1. 标点/空白 → 对应韵律符号（，。！？… 等直接映射进 68 符号表）；
 * 2. 多音词优先按 [PinyinDict.lookupPhrase] 定音，其余汉字逐字查 [PinyinDict.lookupChar]；
 * 3. 拼音 → 声母/韵母 → [PinyinTable] 映射到 68 符号 IPA。
 *
 * 逐字转换，不做分词（词边界/连读留待后续按效果再调）。
 */
class ChineseG2p(context: Context) : LanguageG2p {

    private val appContext = context.applicationContext

    @Volatile
    private var dictReady = false

    private fun ensureDict() {
        if (!dictReady) {
            PinyinDict.init(appContext)
            dictReady = true
        }
    }

    override fun phonemize(text: String): List<String> {
        ensureDict()
        val out = ArrayList<String>()
        val chars = text.toList()
        var i = 0
        while (i < chars.size) {
            val ch = chars[i]
            when {
                ch.isWhitespace() -> out.add(" ")
                ch in PUNCT -> out.add(PUNCT.getValue(ch))
                isCjk(ch) -> {
                    // 多音词优先：匹配以当前字开头的多音词（取最长）
                    val word = matchPolyphone(chars, i)
                    if (word != null) {
                        word.forEach { out.addAll(PinyinTable.pinyinToSymbols(it)) }
                        i += word.size - 1   // 跳过该词剩余字（循环末尾再 +1）
                    } else {
                        PinyinDict.lookupChar(ch)?.let { out.addAll(PinyinTable.pinyinToSymbols(it)) }
                        // 未收录汉字：跳过（不产出表外符号）
                    }
                }
                // 拉丁字母/数字暂不入模型（中文模型对其读音弱），跳过
                else -> Unit
            }
            i++
        }
        return out
    }

    /** 从位置 [start] 起匹配最长多音词，命中返回其拼音序列，否则 null。 */
    private fun matchPolyphone(chars: List<Char>, start: Int): List<String>? {
        for (len in 4 downTo 2) {
            if (start + len > chars.size) continue
            val candidate = chars.subList(start, start + len).joinToString("")
            PinyinDict.lookupPhrase(candidate)?.let { return it }
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
