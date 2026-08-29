package com.meapet.mobile.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 「是否启用检查更新」确认弹窗。
 *
 * 在隐私政策弹窗之后展示（首次启动或隐私政策版本号更新时）。
 * - 「开启」→ 启动时自动检查更新并提示新版本。
 * - 「不开启」→ 不自动检查；仍可在「关于」页手动检测。
 * 选择结果写入 enable_auto_update_check。
 */
@Composable
fun AutoUpdateOptInDialog(
    onEnable: () -> Unit,
    onDisable: () -> Unit
) {
    Dialog(
        onDismissRequest = { /* 必须做出明确选择 */ },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 48.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "检查更新",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text(
                    text = "是否在应用启动时自动检查新版本？\n\n开启后，检测到新版本时会提示你；关闭后你仍可以在「关于」页面手动检测更新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDisable) {
                        Text("不开启")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onEnable) {
                        Text("开启")
                    }
                }
            }
        }
    }
}
