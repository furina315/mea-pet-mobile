package com.meapet.mobile.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ═══════════════════════════════════════════════════
//  设置页公共件：视觉常量、Slider 规格、基础行/分组组件
// ═══════════════════════════════════════════════════

/** 顶栏半透明背景 alpha。 */
internal const val ALPHA_TOP_BAR = 0.85f

/** 次要/说明文字 alpha。 */
internal const val ALPHA_MUTED_TEXT = 0.6f

/** 更淡文字 alpha（提示/列表说明）。 */
internal const val ALPHA_FAINT_TEXT = 0.7f

/** 卡片/行背景表面变体 alpha。 */
internal const val ALPHA_CARD_BG = 0.3f

/** 模型列表卡片背景 alpha。 */
internal const val ALPHA_CARD_BG_MID = 0.45f

/** 分割线 / 禁用文字 alpha。 */
internal const val ALPHA_DIVIDER = 0.4f

/** 滑杆未激活轨道色（深/浅主题）。 */
internal fun sliderTrackColor(darkTheme: Boolean): Color =
    if (darkTheme) Color(0xFF999999).copy(alpha = ALPHA_CARD_BG)
    else Color.White.copy(alpha = 0.35f)

// ── Slider 规格（范围 + 步进） ────────────────────────

internal val TEMPERATURE_RANGE = 0f..2f
internal const val TEMPERATURE_STEPS = 19
internal val MAX_TOKENS_RANGE = 256f..8192f
internal const val MAX_TOKENS_STEPS = 30
internal val SUMMARY_INTERVAL_RANGE = 3f..30f
internal const val SUMMARY_INTERVAL_STEPS = 26

/**
 * 聊天气泡透明度滑杆：0.2~1.0，1.0 不透明（下限 0.2 保证气泡仍可辨识）。
 */
internal val CHAT_BUBBLE_ALPHA_RANGE = 0.2f..1.0f
internal const val CHAT_BUBBLE_ALPHA_STEPS = 15

/** 背景壁纸模糊滑杆：0~1，0 = 不模糊；steps=19 → 均分 20 段，每段恰 0.05。 */
internal val WALLPAPER_BLUR_RANGE = 0f..1f
internal const val WALLPAPER_BLUR_STEPS = 19

/**
 * 壁纸预览盒纵横比（宽 / 高）。竖图源也按此比例 cover 裁剪，避免溢出盒高叠到下方按钮。
 * 2:1 略宽于手机屏宽/盒高（约 2.2:1），视觉舒展。
 */
internal const val PREVIEW_ASPECT_RATIO = 2f

/**
 * 语速滑杆：显示/拖动的是「语速倍率 speed」（0.5=半速慢、2.0=双倍速快），
 * 与内部 `length_scale`（<1 快、>1 慢）互为倒数。steps=14 → 0.5..2.0 间 0.1 步进。
 */
internal val TTS_SPEED_RANGE = 0.5f..2.0f
internal const val TTS_SPEED_STEPS = 14

/** 失焦时保存的扩展（统一 onFocusChanged 样板）。 */
internal fun Modifier.saveOnFocusChange(action: () -> Unit): Modifier =
    onFocusChanged { if (!it.isFocused) action() }

/**
 * 设置卡片：圆角半透明 Surface 容器，可选组标题。
 *
 * 页内每个逻辑小节各占一张卡片，小节标题由各 Section 用 [SectionTitle] 给出，
 * 所以 [title] 通常留空——页名已经在顶栏显示过了。
 */
@Composable
internal fun SettingsCard(
    title: String? = null,
    content: @Composable () -> Unit
) {
    if (title != null) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
        )
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ALPHA_CARD_BG),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            content()
        }
    }
}

@Composable
internal fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
internal fun SettingsSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    darkTheme: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.background,
                uncheckedTrackColor = MaterialTheme.colorScheme.background,
                uncheckedThumbColor = if (darkTheme) MaterialTheme.colorScheme.outline
                                      else Color.White,
            )
        )
    }
}

/** 轻量进度条（包一层避免引入额外 import 差异）。 */
@Composable
internal fun LinearProgressLike(progress: Float) {
    androidx.compose.material3.LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth()
    )
}

/** 轻量选择片（替代 FilterChip，保持现有视觉风格）。 */
@Composable
internal fun FilterChipLike(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(20.dp),
        border = if (selected) androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.primary
        ) else null
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}
