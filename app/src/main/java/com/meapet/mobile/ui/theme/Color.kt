package com.meapet.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// ── 调色工具 ──────────────────────────

private fun Color.lighten(f: Float): Color = copy(
    red = (red + (1f - red) * f).coerceIn(0f, 1f),
    green = (green + (1f - green) * f).coerceIn(0f, 1f),
    blue = (blue + (1f - blue) * f).coerceIn(0f, 1f),
)

private fun Color.darken(f: Float): Color = copy(
    red = (red * (1f - f)).coerceIn(0f, 1f),
    green = (green * (1f - f)).coerceIn(0f, 1f),
    blue = (blue * (1f - f)).coerceIn(0f, 1f),
)

/** 与灰色混合得到去饱和版本。 */
private fun Color.desaturate(f: Float): Color {
    val g = 0.5f
    return copy(
        red = red * (1f - f) + g * f,
        green = green * (1f - f) + g * f,
        blue = blue * (1f - f) + g * f,
    )
}

/**
 * 从单一 seed 主色生成完整浅色方案（模拟动态颜色行为）。
 * 不需要手动为每套预设配副色/容器色。
 *
 * primary/secondary/tertiary 先压暗 0.35 再配近白前景，而非直接用 seed 原色：
 * MD3 浅色方案的 primary 相当于 tone-40（偏暗），原色配深字亮度过近。0.35 是让
 * 12 套预设全部达到 WCAG 4.5:1 的安全下界，改动前请跑 tools/contrast_check.py。
 */
private fun lightScheme(seed: Color, bg: Color = Color(0xFFF8F8F8)): ColorScheme {
    val s = seed.desaturate(0.35f)
    // tertiary 用 seed 的更去饱和变体，保持同色相（触摸气泡用）；
    // 原先 hueShift 交换 G/B 通道会把紫甩成绿、蓝甩成黄绿，与主题色相错位。
    val t = seed.desaturate(0.6f)
    // 用 seed 轻微染 surface/background，让每个预设的菜单/弹窗底色肉眼可辨
    val tintedBg = Color(
        (bg.red * 0.85f + seed.red * 0.15f).coerceIn(0f, 1f),
        (bg.green * 0.85f + seed.green * 0.15f).coerceIn(0f, 1f),
        (bg.blue * 0.85f + seed.blue * 0.15f).coerceIn(0f, 1f),
    )
    return lightColorScheme(
        primary = seed.darken(0.35f), onPrimary = seed.lighten(0.95f),
        primaryContainer = seed.lighten(0.82f), onPrimaryContainer = seed.darken(0.6f),
        secondary = s.darken(0.35f), onSecondary = s.lighten(0.95f),
        secondaryContainer = s.lighten(0.82f), onSecondaryContainer = s.darken(0.6f),
        tertiary = t.darken(0.35f), onTertiary = t.lighten(0.95f),
        tertiaryContainer = t.lighten(0.77f), onTertiaryContainer = t.darken(0.6f),
        background = tintedBg, onBackground = seed.darken(0.5f),
        surface = tintedBg, onSurface = seed.darken(0.5f),
        surfaceVariant = seed.lighten(0.92f), onSurfaceVariant = seed.darken(0.3f),
        outline = seed.lighten(0.3f), outlineVariant = seed.lighten(0.75f),
        // surfaceContainer 系列：M3 弹窗 / 菜单 / 卡片容器默认取这几个，不显式设置
        // 会回退 lightColorScheme 的默认紫调（清除对话确认框底色发紫的根因）。
        // 由 tintedBg 逐档压暗，保持与背景同一色相。
        surfaceContainerLowest = tintedBg,
        surfaceContainerLow = tintedBg.darken(0.015f),
        surfaceContainer = tintedBg.darken(0.03f),
        surfaceContainerHigh = tintedBg.darken(0.05f),
        surfaceContainerHighest = tintedBg.darken(0.07f),
    )
}

private fun darkScheme(seed: Color, bg: Color = Color(0xFF1A1A1A)): ColorScheme {
    val sp = seed.lighten(0.5f)
    val s = sp.desaturate(0.35f)
    // 同 lightScheme：tertiary 用更去饱和变体，保持同色相
    val t = sp.desaturate(0.6f)
    val tintedBg = Color(
        (bg.red * 0.85f + sp.red * 0.15f).coerceIn(0f, 1f),
        (bg.green * 0.85f + sp.green * 0.15f).coerceIn(0f, 1f),
        (bg.blue * 0.85f + sp.blue * 0.15f).coerceIn(0f, 1f),
    )
    return darkColorScheme(
        primary = sp, onPrimary = sp.darken(0.85f),
        primaryContainer = sp.darken(0.6f), onPrimaryContainer = sp.lighten(0.7f),
        secondary = s, onSecondary = s.darken(0.85f),
        secondaryContainer = s.darken(0.6f), onSecondaryContainer = s.lighten(0.7f),
        tertiary = t, onTertiary = t.darken(0.85f),
        tertiaryContainer = t.darken(0.55f), onTertiaryContainer = t.lighten(0.7f),
        background = tintedBg, onBackground = sp.lighten(0.55f),
        surface = tintedBg, onSurface = sp.lighten(0.55f),
        surfaceVariant = sp.darken(0.75f), onSurfaceVariant = sp.lighten(0.4f),
        outline = sp.darken(0.3f), outlineVariant = sp.darken(0.5f),
        // surfaceContainer 系列（同 lightScheme 说明）：深色下逐档抬亮。
        surfaceContainerLowest = tintedBg.darken(0.05f),
        surfaceContainerLow = tintedBg.lighten(0.02f),
        surfaceContainer = tintedBg.lighten(0.04f),
        surfaceContainerHigh = tintedBg.lighten(0.07f),
        surfaceContainerHighest = tintedBg.lighten(0.10f),
    )
}

// ── 预设 seed 色值（仅需定义主色） ──────────

private val S_Default    = Color(0xFF6650A4)
private val S_Ocean      = Color(0xFF006492)
private val S_Forest     = Color(0xFF2E7D32)
private val S_Sunset     = Color(0xFFB85C00)
private val S_Rose       = Color(0xFFB54E6A)
private val S_Mono       = Color(0xFF4A4A4A)
private val S_Sky        = Color(0xFF5B8FA8)
private val S_Sage       = Color(0xFF6B9E7A)
private val S_Lilac      = Color(0xFF9A7FA8)
private val S_Terracotta = Color(0xFFA87A6A)
private val S_Amber      = Color(0xFFB59A5A)
private val S_Steel      = Color(0xFF6A7A8A)

// ── 暴露出去的公开色值（供 Theme.kt 等使用） ──

val Purple80 = S_Default.lighten(0.45f)
val PurpleGrey80 = S_Default.desaturate(0.35f).lighten(0.45f)
val Pink80 = S_Default.desaturate(0.6f).lighten(0.45f)

val Purple40 = S_Default
val PurpleGrey40 = S_Default.desaturate(0.35f)
val Pink40 = S_Default.desaturate(0.6f)

// ── 预设注册 ──────────────────────────

@Immutable
data class ThemePreset(
    val id: String, val name: String, val seed: Color,
    val light: ColorScheme, val dark: ColorScheme,
)

val THEME_PRESETS: List<ThemePreset> = listOf(
    ThemePreset("default",    "紫罗兰", S_Default,    lightScheme(S_Default),    darkScheme(S_Default)),
    ThemePreset("ocean",      "海洋",   S_Ocean,      lightScheme(S_Ocean, Color(0xFFF8FDFF)), darkScheme(S_Ocean, Color(0xFF1A1C1E))),
    ThemePreset("forest",     "森林",   S_Forest,     lightScheme(S_Forest, Color(0xFFF8FDF5)), darkScheme(S_Forest, Color(0xFF1B1F19))),
    ThemePreset("sunset",     "日落",   S_Sunset,     lightScheme(S_Sunset, Color(0xFFFFF8F4)), darkScheme(S_Sunset, Color(0xFF201A16))),
    ThemePreset("rose",       "玫瑰",   S_Rose,       lightScheme(S_Rose, Color(0xFFFFF8F9)),   darkScheme(S_Rose, Color(0xFF201A1C))),
    ThemePreset("mono",       "单色",   S_Mono,       lightScheme(S_Mono),                       darkScheme(S_Mono)),
    ThemePreset("sky",        "晴空",   S_Sky,        lightScheme(S_Sky, Color(0xFFF5FAFC)),     darkScheme(S_Sky, Color(0xFF1A2228))),
    ThemePreset("sage",       "鼠尾草", S_Sage,       lightScheme(S_Sage, Color(0xFFF5FAF6)),    darkScheme(S_Sage, Color(0xFF18201A))),
    ThemePreset("lilac",      "丁香",   S_Lilac,      lightScheme(S_Lilac, Color(0xFFFAF8FC)),   darkScheme(S_Lilac, Color(0xFF1E1A24))),
    ThemePreset("terracotta", "陶土",   S_Terracotta, lightScheme(S_Terracotta, Color(0xFFFCF8F6)), darkScheme(S_Terracotta, Color(0xFF241C18))),
    ThemePreset("amber",      "琥珀",   S_Amber,      lightScheme(S_Amber, Color(0xFFFCFAF2)),   darkScheme(S_Amber, Color(0xFF222016))),
    ThemePreset("steel",      "钢蓝",   S_Steel,      lightScheme(S_Steel, Color(0xFFF4F6F8)),   darkScheme(S_Steel, Color(0xFF161A20))),
)

fun findPreset(id: String): ThemePreset =
    THEME_PRESETS.find { it.id == id } ?: THEME_PRESETS.first()
