package com.meapet.mobile.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole

/**
 * 聊天气泡组件。
 *
 * 根据角色自动选择样式：
 * - 用户消息：靠右 → 蓝色气泡（深色模式变体）
 * - 助手消息：靠左 → 灰色气泡
 * - 系统消息：居中 → 小型提示条
 *
 * @param message 消息
 * @param modifier Modifier
 * @param alpha 气泡整体透明度（0.2~1.0，1.0 不透明），主页聊天列表可调
 */
@Composable
fun ChatBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    alpha: Float = 1f
) {
    when (message.role) {
        ChatRole.user -> UserBubble(message, modifier, alpha)
        ChatRole.assistant -> AssistantBubble(message, modifier, alpha)
        ChatRole.system -> SystemBanner(message, modifier, alpha)
    }
}

@Composable
private fun UserBubble(
    message: ChatMessage,
    modifier: Modifier,
    alpha: Float
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // 长按弹出系统文字选择菜单（复制 / 全选）。SelectionContainer 只接管
            // 长按选择，普通触摸仍在根布局透传给背后 Live2D，不影响立绘点击。
            SelectionContainer {
                Text(
                    text = message.content,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = alpha),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    message: ChatMessage,
    modifier: Modifier,
    alpha: Float
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        // 助手头像（小圆点装饰）
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "M",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = alpha)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                // 助手消息走 Markdown 渲染（代码块/公式/表格/链接），流式时自动补全未闭合围栏。
                // TextView 已开启原生文字选择：长按弹出复制/全选菜单，与链接点击并存。
                MarkdownText(
                    markdown = message.content,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    alpha = alpha,
                    isStreaming = message.isStreaming
                )
            }

            // 流式输出指示
            if (message.isStreaming) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "正在输入...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f * alpha),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SystemBanner(
    message: ChatMessage,
    modifier: Modifier,
    alpha: Float
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f * alpha))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = alpha)
            )
        }
    }
}
