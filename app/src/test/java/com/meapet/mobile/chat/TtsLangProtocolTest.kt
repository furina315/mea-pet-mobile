package com.meapet.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TtsLangProtocol] 骨架单测：日语译本块的剥离与容错。
 */
class TtsLangProtocolTest {

    @Test
    fun `剥离日语块得到正文与译本`() {
        val raw = "没兴趣喵，你一个人去喵。\n\n```tts-ja\n興味ないにゃ、一人で行きなよにゃ。\n```"
        val r = TtsLangProtocol.extract(raw)
        assertEquals("没兴趣喵，你一个人去喵。", r.visibleReply)
        assertEquals("興味ないにゃ、一人で行きなよにゃ。", r.jaText)
        assertEquals("```tts-ja\n興味ないにゃ、一人で行きなよにゃ。\n```", r.rawBlock)
    }

    @Test
    fun `无块时整段为正文`() {
        val raw = "没兴趣喵。"
        val r = TtsLangProtocol.extract(raw)
        assertEquals(raw, r.visibleReply)
        assertNull(r.jaText)
        assertNull(r.rawBlock)
    }

    @Test
    fun `未闭合块保守剥掉不显示碎块`() {
        val raw = "正文在这。\n\n```tts-ja\n未闭合的日语"
        val r = TtsLangProtocol.extract(raw)
        assertEquals("正文在这。", r.visibleReply)
        assertNull(r.jaText)
    }

    @Test
    fun `正文里先提到标记不误匹配`() {
        // 正文里出现 ```tts-ja 字样，真正的块在最后
        val raw = "那个 ```tts-ja 是语音块。\n\n```tts-ja\nこれはテスト。\n```"
        val r = TtsLangProtocol.extract(raw)
        assertEquals("これはテスト。", r.jaText)
        assertEquals("那个 ```tts-ja 是语音块。", r.visibleReply)
    }

    @Test
    fun `jaTextOfBlock 从块原文取译本`() {
        val block = "```tts-ja\n興味ないにゃ。\n```"
        assertEquals("興味ないにゃ。", TtsLangProtocol.jaTextOfBlock(block))
    }
}
