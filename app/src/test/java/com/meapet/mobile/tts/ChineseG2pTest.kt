package com.meapet.mobile.tts

import com.meapet.mobile.tts.g2p.PinyinTable
import com.meapet.mobile.tts.g2p.SymbolTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中文 G2P 与符号表的离线单测（纯 JVM，不依赖 Android 运行时）。
 *
 * 核心断言：映射产出的每个音素符号都必须落在 68 符号表内——表外符号会让
 * 合成静默丢弃音素，是最难排查的正确性问题。
 */
class ChineseG2pTest {

    @Test
    fun `pinyin 映射产出的符号全部在 68 符号表内`() {
        // 覆盖全部声母/韵母/声调的代表性拼音
        val samples = listOf(
            "ba4", "po1", "ma1", "fo2", "de5", "te4", "na3", "le4",
            "ge1", "ke3", "he2", "ji1", "qi2", "xi1",
            "zhi1", "chi2", "shi4", "ri4", "zi3", "ci2", "si4",
            "hao3", "tian1", "qi4", "yin1", "yue4", "kuai4", "nv3", "wo3", "ni3"
        )
        for (py in samples) {
            val symbols = PinyinTable.pinyinToSymbols(py)
            assertTrue("拼音 $py 未产出任何符号", symbols.isNotEmpty())
            val outside = symbols.filterNot { SymbolTable.contains(it) }
            assertTrue("拼音 $py 产出表外符号: $outside", outside.isEmpty())
        }
    }

    @Test
    fun `全量拼音表所有读音映射后都在 68 符号内`() {
        // 读 assets 拼音表（jvm 单测 working dir 为 app/），逐个读音过 PinyinTable
        val file = java.io.File("src/main/assets/pinyin/zh_pinyin.txt")
        assertTrue("拼音表文件不存在: ${file.absolutePath}", file.exists())
        val pinyins = file.readLines(Charsets.UTF_8)
            .mapNotNull { it.split('\t').getOrNull(1) }
            .toSet()
        assertTrue("拼音表为空", pinyins.isNotEmpty())

        val badPy = LinkedHashMap<String, List<String>>()
        val emptyPy = ArrayList<String>()
        for (py in pinyins) {
            val symbols = PinyinTable.pinyinToSymbols(py)
            if (symbols.isEmpty()) {
                emptyPy.add(py)   // 应只剩极个别边缘音节
                continue
            }
            val outside = symbols.filterNot { SymbolTable.contains(it) }
            if (outside.isNotEmpty()) badPy[py] = outside
        }
        assertTrue("以下拼音映射出产表外符号: $badPy", badPy.isEmpty())
        // 补上纯鼻音音节后，空产出的拼音应为 0
        assertTrue("以下拼音未产出任何音素（会漏读）: $emptyPy", emptyPy.isEmpty())
    }

    @Test
    fun `边缘语气词不漏读`() {
        for (py in listOf("ń5", "ǹ5", "ḿ5", "m5", "hm5")) {
            val symbols = PinyinTable.pinyinToSymbols(py)
            assertTrue("拼音 $py 应产出音素", symbols.isNotEmpty())
        }
    }

    @Test
    fun `blank 插入得到 2n+1 长度`() {
        val ids = intArrayOf(10, 20, 30)
        val out = SymbolTable.insertBlank(ids)
        assertEquals(7, out.size)
        assertEquals(0, out[0])
        assertEquals(10, out[1])
        assertEquals(0, out[2])
        assertEquals(20, out[3])
    }

    @Test
    fun `符号表大小与 blank ID 正确`() {
        assertEquals(68, SymbolTable.SIZE)
        assertEquals(0, SymbolTable.BLANK_ID)
        assertEquals(0, SymbolTable.idOf("_"))
        assertEquals(67, SymbolTable.idOf(" "))
    }
}
