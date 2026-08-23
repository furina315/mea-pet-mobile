package com.meapet.mobile.tts

import com.meapet.mobile.tts.g2p.G2pProcessor
import com.meapet.mobile.tts.g2p.LanguageG2p
import com.meapet.mobile.tts.g2p.SymbolTable
import com.meapet.mobile.tts.g2p.TtsLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G2pProcessor 的纯 JVM 单测（依赖注入 fake LanguageG2p，不碰 Android/词典）。
 *
 * 覆盖：空结果兜底（保证合成不空转）+ 句末韵律停顿（`#` 追加）。
 */
class G2pProcessorTest {

    private fun processor(symbols: List<String>) = G2pProcessor(
        chinese = object : LanguageG2p {
            override fun phonemize(text: String): List<String> = symbols
        }
    )

    @Test
    fun `空白文本返回空数组`() {
        assertTrue(processor(emptyList()).textToIds("  ", TtsLanguage.ZH).isEmpty())
        assertTrue(processor(emptyList()).textToIds("", TtsLanguage.ZH).isEmpty())
    }

    @Test
    fun `g2p 产出空符号时兜底为单个空格停顿`() {
        // 模拟全拉丁/数字/生僻字 → phonemize 空 → 兜底必须产出非空 ID（id 67 = 空格）
        val ids = processor(emptyList()).textToIds("OK 2026", TtsLanguage.ZH)
        assertTrue("空符号必须兜底为非空 ID 序列", ids.isNotEmpty())
        // 兜底是 insertBlank([67]) → 长度 3，含空格 id 67
        assertEquals(3, ids.size)
        assertEquals(67, ids[1])
    }

    @Test
    fun `句末补韵律停顿符号`() {
        // 模拟正常中文"你好"（无尾标点）→ 末尾应补 `#`（id 59）
        val ids = processor(listOf("n", "i", "→", "h", "a", "o", "→"))
            .textToIds("你好", TtsLanguage.ZH)
        assertTrue(ids.isNotEmpty())
        // insertBlank 后最后一个非 blank 音素应为 #（id 59）
        assertEquals(59, ids[ids.size - 2])
    }

    @Test
    fun `末尾已有终止标点不重复补`() {
        // 以句号结尾 → 不再追加 `#`，最后非 blank 音素是句号（id 2 = "."）
        val ids = processor(listOf("n", "i", "→", "."))
            .textToIds("你好。", TtsLanguage.ZH)
        assertEquals(2, ids[ids.size - 2])
    }

    @Test
    fun `兜底序列同样能插入 blank`() {
        val ids = processor(listOf("a")).textToIds("a", TtsLanguage.ZH)
        // [blank, a, blank, #, blank]
        assertEquals(5, ids.size)
        assertEquals(SymbolTable.BLANK_ID, ids[0])
        assertEquals(SymbolTable.idOf("#"), ids[3])
    }
}
