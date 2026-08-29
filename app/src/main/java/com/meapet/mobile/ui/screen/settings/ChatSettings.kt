package com.meapet.mobile.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsViewModel
import com.meapet.mobile.settings.SettingsKeys

// ═══════════════════════════════════════════════════
//  对话：System Prompt 与记忆系统
// ═══════════════════════════════════════════════════

/** System Prompt 编辑区。 */
@Composable
internal fun SystemPromptSection(
    viewModel: SettingsViewModel,
    local: SettingsLocalState
) {
    SectionTitle("System Prompt")

    // 内联二次确认：true=已变「确认恢复？」等待再次点击
    var confirmReset by remember { mutableStateOf(false) }

    Column {
        OutlinedTextField(
            value = local.systemPrompt,
            onValueChange = { local.systemPrompt = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .saveOnFocusChange { viewModel.saveSystemPrompt(local.systemPrompt) },
            maxLines = 6
        )

        Spacer(Modifier.height(8.dp))

        // 恢复默认：首次点击变红字「确认恢复？」，再次点击才执行；失焦/超时自动还原
        OutlinedButton(
            onClick = {
                if (confirmReset) {
                    confirmReset = false
                    // 1. 立即更新本地编辑状态（文本框内容瞬间变化）
                    local.systemPrompt = SettingsKeys.Defaults.SYSTEM_PROMPT
                    // 2. 异步写入 DataStore
                    viewModel.resetSystemPrompt()
                } else {
                    confirmReset = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (confirmReset) "确认恢复？" else "恢复默认",
                color = if (confirmReset) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
            )
        }
    }

    // 确认状态 3 秒无操作自动还原，避免误留
    LaunchedEffect(confirmReset) {
        if (confirmReset) {
            kotlinx.coroutines.delay(3000)
            confirmReset = false
        }
    }
}


/** 记忆系统：开关 + 摘要轮次滑杆。 */
@Composable
internal fun MemorySection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    local: SettingsLocalState,
    darkTheme: Boolean
) {
    SectionTitle("记忆系统")

    SettingsSwitchRow(
        label = "启用记忆",
        description = "保留对话中提取的重要信息",
        checked = state.enableMemory,
        darkTheme = darkTheme,
        onCheckedChange = { viewModel.updateEnableMemory(it) }
    )
    SettingsSwitchRow(
        label = "自动摘要",
        description = "定期总结对话为长期记忆",
        checked = state.enableAutoSummary,
        darkTheme = darkTheme,
        onCheckedChange = { viewModel.updateEnableAutoSummary(it) }
    )

    Spacer(Modifier.height(8.dp))

    Text(
        text = "摘要轮次: 每 ${local.summaryInterval.toInt()} 轮对话总结一次",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
            alpha = if (state.enableAutoSummary) 1f else 0.5f
        )
    )
    Slider(
        value = local.summaryInterval,
        onValueChange = { local.summaryInterval = it },
        onValueChangeFinished = {
            viewModel.updateSummaryInterval(local.summaryInterval.toInt())
        },
        valueRange = SUMMARY_INTERVAL_RANGE,
        steps = SUMMARY_INTERVAL_STEPS,
        enabled = state.enableAutoSummary,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(
            inactiveTrackColor = sliderTrackColor(darkTheme)
        )
    )
}
