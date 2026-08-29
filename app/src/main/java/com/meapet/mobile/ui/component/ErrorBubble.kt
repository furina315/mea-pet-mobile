package com.meapet.mobile.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meapet.mobile.R

/**
 * 对话流内的错误卡片，取代原先的错误 Snackbar。
 *
 * 落在最后一条消息之后随对话滚动，用户能看清是哪一轮失败，也不会因 Snackbar
 * 自动消失而错过重试。错误是瞬态 UI 状态，不进 ChatMessage、不写会话历史。
 *
 * @param message 错误文案，多行完整展示不截断
 * @param onDismiss 关闭（右上角）
 * @param onRetry 重试上一条消息（右下角）
 * @param alpha 气泡透明度，跟随「外观 → 气泡透明度」设置
 */
@Composable
fun ErrorBubble(
    message: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    alpha: Float = 1f,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = alpha)
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
        ) {
            // 标题行：图标 + 标题 + 右上角关闭
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_error),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = alpha),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "发送失败",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = alpha),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = alpha),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 错误正文：多行完整展示，不设 maxLines
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = alpha),
                lineHeight = 20.sp,
                modifier = Modifier.padding(end = 8.dp)
            )

            Spacer(Modifier.height(4.dp))

            // 右下角重试
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onRetry) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = alpha),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "重试",
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = alpha),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
