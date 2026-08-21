package com.meapet.mobile.chat

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 消息角色枚举。
 */
@Serializable
enum class ChatRole { system, user, assistant }

/**
 * 聊天消息领域模型。
 *
 * 该模型贯穿整个聊天子系统，从 ChatService → ConversationManager → ViewModel → UI。
 * 可序列化以支持会话历史持久化（[ConversationStore]）。
 *
 * @property role 消息角色
 * @property content 消息文本内容（助手消息已剥离记忆协议块，即用户看到的正文）
 * @property memoryOpsBlock 助手回复里附带的记忆协议块原文（含围栏），无则为 null。
 *   不参与 UI 展示，仅在组装 API 请求时贴回历史——模型是照着自己过去的回复学格式的，
 *   历史里若全是「没有块」的回复，system prompt 里写多少遍「必须输出」都压不过这份实证。
 * @property id 唯一标识（UUID）
 * @property timestamp 消息时间戳
 * @property isStreaming 是否正在流式输出中（运行时状态，不持久化）
 */
@Serializable
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val memoryOpsBlock: String? = null,
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    @Transient val isStreaming: Boolean = false
) {
    /** 是否为用户消息。 */
    val isUser: Boolean get() = role == ChatRole.user

    /** 是否为助手消息。 */
    val isAssistant: Boolean get() = role == ChatRole.assistant

    /** 是否为系统消息。 */
    val isSystem: Boolean get() = role == ChatRole.system
}
