package com.meapet.mobile.tts.g2p

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 拼音词典：单字 + 词组两级注音。
 *
 * ## 数据源（assets/pinyin/）
 * - `zh_pinyin.txt`：全量单字注音表（`字<TAB>拼音带调号`，41923 字，kMandarin 规范）。
 * - `zh_phrases.txt`：词组注音表（`词语<TAB>拼1 拼2 ...`，pypinyin 词组词典），
 *   用于多音字按词定音 + 配合 jieba 分词产出词边界。
 *
 * 全部在 [init] 首次使用时加载；表缺失时回退到内置少量常用字，保证链路不空转。
 */
object PinyinDict {

    private const val TAG = "PinyinDict"
    private const val CHAR_PATH = "pinyin/zh_pinyin.txt"
    private const val PHRASE_PATH = "pinyin/zh_phrases.txt"

    @Volatile
    private var charTable: Map<Char, String> = emptyMap()

    @Volatile
    private var phraseTable: Map<String, List<String>> = emptyMap()

    @Volatile
    private var loaded = false

    /** 离线兜底：极少量常用字（表缺失时不至于完全无声）。 */
    private val FALLBACK: Map<Char, String> = mapOf(
        '我' to "wo3", '你' to "ni3", '他' to "ta1", '她' to "ta1", '是' to "shi4",
        '的' to "de5", '了' to "le5", '在' to "zai4", '有' to "you3", '和' to "he2",
        '不' to "bu4", '好' to "hao3", '天' to "tian1", '气' to "qi4", '真' to "zhen1",
        '今' to "jin1", '日' to "ri4", '喵' to "miao1", '没' to "mei2", '兴' to "xing4",
        '趣' to "qu4", '一' to "yi1", '个' to "ge4", '人' to "ren2", '去' to "qu4",
        '们' to "men5", '起' to "qi3", '散' to "san4", '步' to "bu4", '吧' to "ba5"
    )

    /** 从 assets 加载单字表 + 词组表。幂等；失败仅记日志。 */
    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        charTable = loadChars(context)
        phraseTable = loadPhrases(context)
        Log.i(TAG, "拼音表加载完成：单字 ${charTable.size}，词组 ${phraseTable.size}")
    }

    private fun loadChars(context: Context): Map<Char, String> = try {
        val map = HashMap<Char, String>(21000)
        readLines(context, CHAR_PATH) { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) {
                val ch = line[0]
                val py = line.substring(tab + 1).trim()
                if (py.isNotEmpty() && !map.containsKey(ch)) map[ch] = py
            }
        }
        map
    } catch (e: Exception) {
        Log.w(TAG, "单字表加载失败，回退到内置常用字", e)
        FALLBACK
    }

    private fun loadPhrases(context: Context): Map<String, List<String>> = try {
        val map = HashMap<String, List<String>>(47000)
        readLines(context, PHRASE_PATH) { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) {
                val word = line.substring(0, tab)
                val seq = line.substring(tab + 1).trim().split(' ').filter { it.isNotEmpty() }
                if (word.isNotEmpty() && seq.isNotEmpty()) map[word] = seq
            }
        }
        map
    } catch (e: Exception) {
        Log.w(TAG, "词组表加载失败（多音字按单字常用音处理）", e)
        emptyMap()
    }

    private inline fun readLines(context: Context, path: String, crossinline onLine: (String) -> Unit) {
        context.assets.open(path).use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.forEachLine { line ->
                    if (line.isNotBlank() && !line.startsWith("#")) onLine(line)
                }
            }
        }
    }

    /** 查单字拼音（带调号）；未收录返回 null。 */
    fun lookupChar(char: Char): String? = (if (charTable.isEmpty()) FALLBACK else charTable)[char]

    /** 查词组各字拼音序列；未收录返回 null（调用方回退逐字查 [lookupChar]）。 */
    fun lookupPhrase(word: String): List<String>? = phraseTable[word]

    /** 词组表是否已加载（供分词侧判断能否用词级定音）。 */
    fun hasPhrases(): Boolean = phraseTable.isNotEmpty()
}
