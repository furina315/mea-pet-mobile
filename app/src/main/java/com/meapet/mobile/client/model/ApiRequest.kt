package com.meapet.mobile.client.model

import com.meapet.mobile.client.MultipartPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val json = Json { encodeDefaults = false }

/**
 * OpenAI 兼容 API 请求体构建辅助函数。
 *
 * 仅负责生成 JSON 字符串或多媒体表单部件，不定义任何业务领域数据类。
 * 调用方可将返回值直接传入 [com.meapet.mobile.client.OpenAiCompatibleClient]。
 */
object ApiRequest {

    /**
     * 构建 `/chat/completions` 请求体。
     *
     * @param messages 消息列表，由 [textMessage] 或 [visionMessage] 生成
     */
    fun chatCompletion(
        model: String,
        messages: List<JsonObject>,
        temperature: Double? = null,
        maxTokens: Int? = null,
        stream: Boolean = false
    ): String = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray { messages.forEach { add(it) } })
        temperature?.let { put("temperature", it) }
        maxTokens?.let { put("max_tokens", it) }
        put("stream", stream)
    }.let { json.encodeToString(it) }

    /** 纯文本消息。 */
    fun textMessage(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    /** 多模态（Vision）消息，content 为 text / image_url 数组。 */
    fun visionMessage(role: String, content: List<JsonObject>): JsonObject = buildJsonObject {
        put("role", role)
        put("content", buildJsonArray { content.forEach { add(it) } })
    }

    /** 文本内容片段。 */
    fun textContent(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    /** 图片 URL 内容片段。 */
    fun imageUrlContent(url: String, detail: String? = null): JsonObject = buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject {
            put("url", url)
            detail?.let { put("detail", it) }
        })
    }

    /**
     * 构建 `/audio/transcriptions` 所需的 multipart/form-data 部件列表。
     *
     * @param contentType 音频文件 MIME 类型，例如 `audio/mpeg`、`audio/wav`、`audio/mp4`
     */
    fun transcriptionParts(
        file: ByteArray,
        filename: String,
        model: String,
        language: String? = null,
        prompt: String? = null,
        responseFormat: String? = null,
        temperature: Double? = null,
        contentType: String = "audio/mpeg"
    ): List<MultipartPart> = buildList {
        add(
            MultipartPart(
                name = "file",
                filename = filename,
                contentType = contentType,
                body = file
            )
        )
        add(textPart("model", model))
        language?.let { add(textPart("language", it)) }
        prompt?.let { add(textPart("prompt", it)) }
        responseFormat?.let { add(textPart("response_format", it)) }
        temperature?.let { add(textPart("temperature", it.toString())) }
    }

    /**
     * 构建 `/audio/speech` 请求体。
     */
    fun speech(
        model: String,
        input: String,
        voice: String,
        responseFormat: String? = null,
        speed: Double? = null
    ): String = buildJsonObject {
        put("model", model)
        put("input", input)
        put("voice", voice)
        responseFormat?.let { put("response_format", it) }
        speed?.let { put("speed", it) }
    }.let { json.encodeToString(it) }

    private fun textPart(name: String, value: String): MultipartPart = MultipartPart(
        name = name,
        filename = null,
        contentType = null,
        body = value.encodeToByteArray()
    )
}
