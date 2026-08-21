package com.meapet.mobile.tts.g2p

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 拼音词典：汉字 → 拼音（末位声调数字 1-4，轻声 5，ü 记作 v）。
 *
 * ## 数据源
 * 主数据来自 assets 的 `pinyin/zh_pinyin.txt`（全量逐字注音表，格式 `字<TAB>拼音`，
 * 多音字一行一个读音、取首行为常用音），经 [init] 在首次使用前加载。
 * 未加载或表缺失时，回退到内置的少量常用字（[FALLBACK]），保证链路不空转。
 *
 * ## 多音字
 * 逐字注音不含词级多音字消歧；[POLYPHONE_WORDS] 存高频多音词的正确读音，
 * 由 [ChineseG2p] 在分词层面优先匹配（本期先做字级 + 少量词级修正）。
 */
object PinyinDict {

    private const val TAG = "PinyinDict"
    private const val ASSET_PATH = "pinyin/zh_pinyin.txt"

    /** 全量表（字 → 拼音带调号）。未加载时为空，回退到 [FALLBACK]。 */
    @Volatile
    private var table: Map<Char, String> = emptyMap()

    @Volatile
    private var loaded = false

    /** 常见多音词 → 各字拼音序列（词级定音，优先于逐字）。 */
    val POLYPHONE_WORDS: Map<String, List<String>> = mapOf(
        "音乐" to listOf("yin1", "yue4"),
        "快乐" to listOf("kuai4", "le4"),
        "重要" to listOf("zhong4", "yao4"),
        "重复" to listOf("chong2", "fu4"),
        "银行" to listOf("yin2", "hang2"),
        "行走" to listOf("xing2", "zou3"),
        "长大" to listOf("zhang3", "da4"),
        "长安" to listOf("chang2", "an1"),
        "担心" to listOf("dan1", "xin1"),
        "重担" to listOf("zhong4", "dan4")
    )

    /** 离线兜底：极少量常用字（表缺失时不至于完全无声）。 */
    private val FALLBACK: Map<Char, String> = mapOf(
        '我' to "wo3", '你' to "ni3", '他' to "ta1", '她' to "ta1", '是' to "shi4",
        '的' to "de5", '了' to "le5", '在' to "zai4", '有' to "you3", '和' to "he2",
        '不' to "bu4", '好' to "hao3", '天' to "tian1", '气' to "qi4", '真' to "zhen1",
        '今' to "jin1", '日' to "ri4", '喵' to "miao1", '没' to "mei2", '兴' to "xing4",
        '趣' to "qu4", '一' to "yi1", '个' to "ge4", '人' to "ren2", '去' to "qu4",
        '们' to "men5", '起' to "qi3", '散' to "san4", '步' to "bu4", '吧' to "ba5"
    )

    /** 从 assets 加载全量拼音表。幂等；失败仅记日志（回退 FALLBACK）。 */
    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val map = HashMap<Char, String>(21000)
            context.assets.open(ASSET_PATH).use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith("#")) return@forEachLine
                        val tab = line.indexOf('\t')
                        if (tab <= 0) return@forEachLine
                        val ch = line[0]
                        val py = line.substring(tab + 1).trim()
                        // 多音字取首行（常用音），后续读音忽略
                        if (py.isNotEmpty() && !map.containsKey(ch)) map[ch] = py
                    }
                }
            }
            table = map
            Log.i(TAG, "拼音表加载完成：${map.size} 字")
        } catch (e: Exception) {
            Log.w(TAG, "拼音表缺失或加载失败，回退到内置常用字（${FALLBACK.size} 字）", e)
            table = FALLBACK
        }
    }

    /**
     * 查单字拼音（带调号）。未收录返回 null。
     * 使用前需先 [init]；未 init 时直接用兜底表（不阻塞）。
     */
    fun lookup(char: Char): String? = (if (table.isEmpty()) FALLBACK else table)[char]

    /** 是否已收录。 */
    fun contains(char: Char): Boolean = lookup(char) != null
}
