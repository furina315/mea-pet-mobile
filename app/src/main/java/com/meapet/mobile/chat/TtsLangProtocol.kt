package com.meapet.mobile.chat

import android.util.Log

/**
 * 模型 ↔ 应用的「语音语言协议」骨架（仿 [com.meapet.mobile.memory.MemoryOpsProtocol]）。
 *
 * 默认语音设为日文时，要求模型在回复末尾附一个 fenced 块（语言标记 [FENCE_LANG]），
 * 内为**与正文逐句对应的日语译本**。该块对用户不可见：
 * - 展示给用户的是剥离后的正文（中文）；
 * - 语音合成只读块里的日语译本；
 * - 组装下一轮历史时按当前语言剥离（中文态）或替换正文（日文态），见
 *   [ConversationManager] 的回贴逻辑——避免译本块长期占用上下文。
 *
 * ## 容错原则（与记忆协议一致）
 * 日语块是锦上添花，绝不能影响正常聊天：
 * - 块缺失/格式异常 → 静默跳过，`jaText` 为 null，语音回退朗读正文；
 * - 剥后正文为空白 → 保守回退整段原文当可见回复。
 *
 * **本期为骨架**：协议解析 / 注入剥离 / 字段全部就位，但日语 G2P 精度调优、
 * 英语支持、混合语言细分不做完整，后续迭代补全。
 */
object TtsLangProtocol {

    private const val TAG = "TtsLangProtocol"

    /** fenced 块的语言标记：` ```tts-ja ` */
    const val FENCE_LANG = "tts-ja"

    private const val FENCE = "```"

    /**
     * 起始围栏 ```tts-ja。与记忆协议同理：必须锚定「最后一个起始围栏」再往后找收尾，
     * 防止正文先提到标记时误匹配（见 MemoryOpsProtocol 的注释）。
     */
    private val openFenceRegex = Regex("$FENCE[ \\t]*$FENCE_LANG", RegexOption.IGNORE_CASE)

    /**
     * @property visibleReply 剥离译本块后的可见回复（正文）
     * @property jaText 块内的日语译本；无块/未闭合/异常时为 null（语音回退读 visibleReply）
     * @property rawBlock 块原文（含围栏），供组装下一轮请求时按语言策略处理
     */
    data class ParseResult(
        val visibleReply: String,
        val jaText: String? = null,
        val rawBlock: String? = null
    )

    /**
     * 协议说明，拼入 system prompt（仅默认语音=日文时调用）。
     *
     * 措辞要点照抄记忆协议的踩坑注释：显式豁免人设字数限制、强调块不属于台词、
     * 正文里绝不提及。译本必须与正文逐句对应——语音合成只读这一块。
     */
    fun instructions(): String = """
        【语音协议】
        你必须在每轮回复的最后附加一个 ```$FENCE_LANG 块，内容是与正文**逐句对应**的日语译本。
        它不展示给用户，正文里绝不要提及；它也**不属于你的台词**——人设的字数上限与语气要求
        一概不适用，不要为了简短而省略它。正文用中文照常回复，块里只写对应的日语翻译。

        ```$FENCE_LANG
        （正文的日语译本，逐句对应，不加任何额外说明）
        ```
    """.trimIndent()

    /**
     * 从模型原始回复中剥离日语译本块。
     *
     * 在记忆协议块剥离**之后**调用（记忆块位置更靠后、已由 MemoryOpsProtocol 处理）。
     */
    fun extract(rawReply: String): ParseResult {
        val open = openFenceRegex.findAll(rawReply).lastOrNull()
            ?: return ParseResult(rawReply, null, null)

        val bodyStart = open.range.last + 1
        val closeIdx = rawReply.indexOf(FENCE, startIndex = bodyStart)
        val blockEnd = if (closeIdx >= 0) closeIdx + FENCE.length else rawReply.length

        val visible = (
            rawReply.substring(0, open.range.first) + rawReply.substring(blockEnd)
            ).trim()
        if (visible.isBlank()) {
            // 剥后没有可见内容，保守回退整段原文，不瞎猜
            return ParseResult(rawReply, null, null)
        }

        if (closeIdx < 0) {
            Log.w(TAG, "未闭合的 tts-ja 块，本轮忽略语音译本")
            return ParseResult(visible, null, null)
        }

        val jaText = rawReply.substring(bodyStart, closeIdx).trim().takeIf { it.isNotEmpty() }
        return ParseResult(
            visibleReply = visible,
            jaText = jaText,
            rawBlock = rawReply.substring(open.range.first, blockEnd)
        )
    }

    /**
     * 从块原文（含围栏）里取出日语译本文本。供语音合成直接读取已存的
     * [com.meapet.mobile.chat.ChatMessage.ttsJaBlock]。
     */
    fun jaTextOfBlock(rawBlock: String): String? {
        val open = openFenceRegex.find(rawBlock) ?: return null
        val bodyStart = open.range.last + 1
        val closeIdx = rawBlock.indexOf(FENCE, startIndex = bodyStart)
        if (closeIdx < 0) return null
        return rawBlock.substring(bodyStart, closeIdx).trim().takeIf { it.isNotEmpty() }
    }
}
