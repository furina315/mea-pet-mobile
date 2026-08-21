package com.meapet.mobile.tts.g2p

/**
 * 英语 G2P（占位，本期不开放）。
 *
 * 框架留接口：英语走 cmudict / espeak 风格 G2P 映射到 68 符号。设置里不开放英文选项，
 * 本类暂返回空，后续迭代补全。
 */
class EnglishG2p : LanguageG2p {
    override fun phonemize(text: String): List<String> = emptyList()
}
