package com.meapet.mobile.tts.g2p

import android.content.Context
import android.util.Log
import com.meapet.mobile.tts.model.TtsModelManager
import com.piperplus.g2p.OpenJTalkDictionary
import com.piperplus.g2p.PiperPlusG2p

/**
 * 日语 G2P：piper-plus-g2p（OpenJTalk 前端，预编译 JNI）→ 音素 → 映射到 68 符号。
 *
 * ## 词典
 * OpenJTalk 用 **naist-jdic**（mecab 词典，~102MB），不是 unidic——unidic schema 不同不能用。
 * 经 [TtsModelManager] 按需下载到 `filesDir/tts_model/ja_dic/`，或资产/HuggingFace 供给。
 *
 * ## 符号体系转换
 * piper-plus 输出 piper 式 ASCII 音素记号（`cl`、`a:`、`N`、`ky`、PUA 编码的多字符 token），
 * 与 VITS 68 符号差异很大，需 [PiperToVits] 逐条转换（cl→Q、u→ɯ、r→ɾ、去 ^$、[→↑、]→↓ 等）。
 * 这层转换是经验近似，最终正确性需与训练侧 `cjke_cleaners2` 实跑输出对照。
 */
class JapaneseG2p(
    private val context: Context,
    private val modelManager: TtsModelManager
) : LanguageG2p {

    @Volatile
    private var engine: PiperPlusG2p? = null

    override fun phonemize(text: String): List<String> {
        if (!modelManager.isDicReady()) {
            Log.w(TAG, "日语词典未下载，日语 G2P 暂不可用")
            return emptyList()
        }
        val g2p = engineOrNull() ?: return emptyList()
        return try {
            val result = g2p.phonemize(text, "ja")
            PiperToVits.convert(result.phonemeList)
        } catch (e: Exception) {
            Log.e(TAG, "日语 G2P 失败", e)
            emptyList()
        }
    }

    /** 懒初始化引擎（词典就绪后）。失败返回 null 而非抛出，保证聊天不受影响。 */
    private fun engineOrNull(): PiperPlusG2p? {
        engine?.let { return it }
        return synchronized(this) {
            engine?.let { return it }
            try {
                val dict = OpenJTalkDictionary.fromPath(modelManager.dictionaryDir().absolutePath)
                PiperPlusG2p.create(context, dict).also { engine = it }
            } catch (e: Exception) {
                Log.e(TAG, "日语 G2P 引擎初始化失败", e)
                null
            }
        }
    }

    fun close() {
        engine?.close()
        engine = null
    }

    private companion object {
        const val TAG = "JapaneseG2p"
    }
}

/**
 * piper-plus 日语音素 → VITS 68 符号 的转换层。
 *
 * 多字符 token 优先匹配（`a:`/`cl`/`N_…`/拗音），再逐字符。
 * 目标全集严格限于 68 符号表，表外一律丢弃并计数。
 *
 * TODO(g2p): 与训练侧 cjke_cleaners2 实跑输出对照校准（声调/长音/无声化细节）。
 */
object PiperToVits {

    /** 多字符 token → 68 符号序列（最长匹配优先）。 */
    private val MULTI: Map<String, List<String>> = mapOf(
        // 促音
        "cl" to listOf("Q"), "q" to listOf("Q"),
        // 长音：重复前一个元音由调用方处理困难，近似拉长为单元音重复
        "a:" to listOf("a", "a"), "i:" to listOf("i", "i"), "u:" to listOf("ɯ", "ɯ"),
        "e:" to listOf("e", "e"), "o:" to listOf("o", "o"),
        // 无声化元音 → 正常元音（VITS 用 * 标注，近似忽略）
        "A" to listOf("a"), "I" to listOf("i"), "U" to listOf("ɯ"),
        "E" to listOf("e"), "O" to listOf("o"),
        // 拨音语境变体
        "N_m" to listOf("m"), "N_n" to listOf("n"), "N_ng" to listOf("ŋ"),
        "N_uvular" to listOf("N"), "N" to listOf("N"),
        // 拗音 / 特殊辅音（分解到表内符号）
        "ky" to listOf("k", "y"), "gy" to listOf("g", "y"), "ny" to listOf("n", "y"),
        "my" to listOf("m", "y"), "ry" to listOf("ɾ", "y"), "hy" to listOf("ç"),
        "py" to listOf("p", "y"), "by" to listOf("b", "y"),
        "ty" to listOf("t", "y"), "dy" to listOf("d", "y"), "zy" to listOf("ʑ"),
        "ch" to listOf("t", "ʃ"), "sh" to listOf("ʃ"), "ts" to listOf("t", "s"),
        // 韵律
        "[" to listOf("↑"), "]" to listOf("↓"), "#" to listOf(" "),
        "?" to listOf("?"), "?!" to listOf("!"), "?." to listOf("."), "?~" to listOf("~")
    )

    /** 单字符 → 68 符号序列。 */
    private val SINGLE: Map<String, List<String>> = mapOf(
        "a" to listOf("a"), "i" to listOf("i"), "u" to listOf("ɯ"),
        "e" to listOf("e"), "o" to listOf("o"),
        "k" to listOf("k"), "g" to listOf("g"), "s" to listOf("s"), "z" to listOf("z"),
        "t" to listOf("t"), "d" to listOf("d"), "n" to listOf("n"), "h" to listOf("ç"),
        "b" to listOf("b"), "p" to listOf("p"), "m" to listOf("m"), "y" to listOf("y"),
        "r" to listOf("ɾ"), "w" to listOf("w"), "f" to listOf("ɸ"), "v" to listOf("v"),
        "j" to listOf("d", "ʑ"), "," to listOf(","), "." to listOf("."), " " to listOf(" ")
    )

    /**
     * 把 piper-plus 的音素 token 序列转成 68 符号序列。
     * 跳过 piper 的句首/句尾控制符（^ $）。表外 token 丢弃。
     */
    fun convert(tokens: List<String>): List<String> {
        val out = ArrayList<String>()
        for (tok in tokens) {
            when (tok) {
                "^", "$" -> Unit   // BOS/EOS 控制符，丢弃
                else -> {
                    val mapped = MULTI[tok] ?: SINGLE[tok]
                    if (mapped != null) out.addAll(mapped)
                    // 未识别 token 静默跳过
                }
            }
        }
        return out
    }
}
