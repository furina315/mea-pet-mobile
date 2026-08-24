package com.meapet.mobile.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meapet.mobile.ui.theme.THEME_PRESETS
import com.meapet.mobile.ui.theme.isDarkTheme
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsViewModel
import com.meapet.mobile.settings.SettingsKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


// ── 视觉常量（语义命名，避免魔法数字） ──────────────────

/** 顶栏半透明背景 alpha。 */
private const val ALPHA_TOP_BAR = 0.85f

/** 次要/说明文字 alpha。 */
private const val ALPHA_MUTED_TEXT = 0.6f

/** 更淡文字 alpha（提示/列表说明）。 */
private const val ALPHA_FAINT_TEXT = 0.7f

/** 卡片/行背景表面变体 alpha。 */
private const val ALPHA_CARD_BG = 0.3f

/** 模型列表卡片背景 alpha。 */
private const val ALPHA_CARD_BG_MID = 0.45f

/** 分割线 / 禁用文字 alpha。 */
private const val ALPHA_DIVIDER = 0.4f

/** 滑杆未激活轨道色（深/浅主题）。 */
private fun sliderTrackColor(darkTheme: Boolean): Color =
    if (darkTheme) Color(0xFF999999).copy(alpha = ALPHA_CARD_BG)
    else Color.White.copy(alpha = 0.35f)

// ── Slider 规格（范围 + 步进） ────────────────────────

private val TEMPERATURE_RANGE = 0f..2f
private const val TEMPERATURE_STEPS = 19
private val MAX_TOKENS_RANGE = 256f..8192f
private const val MAX_TOKENS_STEPS = 30
private val SUMMARY_INTERVAL_RANGE = 3f..30f
private const val SUMMARY_INTERVAL_STEPS = 26

/**
 * 聊天气泡透明度滑杆：0.2~1.0，1.0 不透明（下限 0.2 保证气泡仍可辨识）。
 */
private val CHAT_BUBBLE_ALPHA_RANGE = 0.2f..1.0f
private const val CHAT_BUBBLE_ALPHA_STEPS = 15

/** 背景壁纸模糊滑杆：0~1，0 = 不模糊；steps=19 → 均分 20 段，每段恰 0.05。 */
private val WALLPAPER_BLUR_RANGE = 0f..1f
private const val WALLPAPER_BLUR_STEPS = 19

/**
 * 壁纸预览盒纵横比（宽 / 高）。竖图源也按此比例 cover 裁剪，避免溢出盒高叠到下方按钮。
 * 2:1 略宽于手机屏宽/盒高（约 2.2:1），视觉舒展。
 */
private const val PREVIEW_ASPECT_RATIO = 2f

/**
 * 语速滑杆：显示/拖动的是「语速倍率 speed」（0.5=半速慢、2.0=双倍速快），
 * 与内部 `length_scale`（<1 快、>1 慢）互为倒数。steps=14 → 0.5..2.0 间 0.1 步进。
 */
private val TTS_SPEED_RANGE = 0.5f..2.0f
private const val TTS_SPEED_STEPS = 14

/** 失焦时保存的扩展（统一 onFocusChanged 样板）。 */
private fun Modifier.saveOnFocusChange(action: () -> Unit): Modifier =
    onFocusChanged { if (!it.isFocused) action() }

/**
 * 设置页面。
 *
 * 主体按功能拆分为 6 个 Section：[ApiConfigSection]、[ModelParamsSection]、
 * [SystemPromptSection]、[MemorySection]、[ThemeSection]、[PrivacySection]；
 * 本地编辑状态封装在 [SettingsLocalState]，本函数只负责状态管理与编排。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit = {},
    onExitApp: () -> Unit = {},
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val state by settingsViewModel.state.collectAsState()

    // 深色与否跟随应用内主题设置（与页面整体配色取值一致），仅"跟随系统"时看系统
    val darkTheme = isDarkTheme(state.themeMode)

    // 本地编辑状态（进入页面时取一次已存值，失焦/离开页面时才写回）
    val local = rememberSettingsLocalState(state)

    // 离开页面时兜底保存（焦点还留在输入框内的场景）
    DisposableEffect(Unit) {
        onDispose { local.persist(settingsViewModel) }
    }

    // 从列表点选模型时，同步本地输入框
    LaunchedEffect(state.model) {
        if (local.model != state.model) local.model = state.model
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = ALPHA_TOP_BAR)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ── 对话：模型接入与对话行为 ──
            SettingsGroup(title = "对话") {
                ApiConfigSection(state, settingsViewModel, local)
                ModelParamsSection(state, settingsViewModel, local, darkTheme)
                SystemPromptSection(settingsViewModel, local)
                MemorySection(state, settingsViewModel, local, darkTheme)
            }

            // ── 外观：气泡与主题 ──
            SettingsGroup(title = "外观") {
                AppearanceSection(state, settingsViewModel, darkTheme)
            }

            // ── 语音：TTS ──
            SettingsGroup(title = "语音") {
                TtsSection(state, settingsViewModel, darkTheme)
            }

            // ── 更新 ──
            SettingsGroup(title = "更新") {
                UpdateSection(state, settingsViewModel, darkTheme)
            }

            // ── 隐私与数据 ──
            SettingsGroup(title = "隐私与数据") {
                PrivacySection(state, settingsViewModel, onOpenPrivacyPolicy, onExitApp)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 本地编辑状态 holder。
 *
 * 进入页面时从 [SettingsUiState] 取一次初值，之后独立于 state（失焦才写回），
 * 避免 DataStore 流更新把用户正在编辑的内容覆盖掉。
 */
private class SettingsLocalState(initial: SettingsUiState) {
    var apiKey by mutableStateOf(initial.apiKey)
    var apiUrl by mutableStateOf(initial.apiUrl)
    var model by mutableStateOf(initial.model)
    var systemPrompt by mutableStateOf(initial.systemPrompt)
    var temperature by mutableStateOf(initial.temperature.toFloat())
    var maxTokens by mutableStateOf(initial.maxTokens.toFloat())
    var summaryInterval by mutableStateOf(initial.summaryInterval.toFloat())
    var apiKeyVisible by mutableStateOf(false)

    /** 离开页面时的兜底保存（值有变化才落盘，见 ViewModel）。 */
    fun persist(viewModel: SettingsViewModel) {
        viewModel.saveApiKey(apiKey)
        viewModel.saveApiUrl(apiUrl)
        viewModel.saveModel(model)
        viewModel.saveSystemPrompt(systemPrompt)
    }
}

@Composable
private fun rememberSettingsLocalState(state: SettingsUiState): SettingsLocalState =
    remember { SettingsLocalState(state) }

// ═══════════════════════════════════════════════════
//  Section 组件
// ═══════════════════════════════════════════════════

/** API 配置：端点说明 + API Key / 地址输入。 */
@Composable
private fun ApiConfigSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    local: SettingsLocalState
) {
    SectionTitle("API 配置")

    Text(
        "需要一个 OpenAI 兼容的 API 端点",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT),
        modifier = Modifier.padding(bottom = 8.dp)
    )

    OutlinedTextField(
        value = local.apiKey,
        onValueChange = { local.apiKey = it },
        label = { Text("API Key") },
        placeholder = { Text("sk-...") },
        modifier = Modifier
            .fillMaxWidth()
            .saveOnFocusChange { viewModel.saveApiKey(local.apiKey) },
        singleLine = true,
        visualTransformation = if (local.apiKeyVisible)
            VisualTransformation.None
        else
            PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(
                onClick = { local.apiKeyVisible = !local.apiKeyVisible },
                modifier = Modifier.width(56.dp)
            ) {
                Text(
                    text = if (local.apiKeyVisible) "隐藏" else "显示",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
    )

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = local.apiUrl,
        onValueChange = { local.apiUrl = it },
        label = { Text("API 地址") },
        modifier = Modifier
            .fillMaxWidth()
            .saveOnFocusChange { viewModel.saveApiUrl(local.apiUrl) },
        singleLine = true,
        placeholder = { Text("https://api.deepseek.com/v1") }
    )
}

/** 模型参数：模型名 + 拉取列表 + Temperature / MaxToken 滑杆。 */
@Composable
private fun ModelParamsSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    local: SettingsLocalState,
    darkTheme: Boolean
) {
    SectionTitle("模型参数")

    OutlinedTextField(
        value = local.model,
        onValueChange = { local.model = it },
        label = { Text("模型") },
        modifier = Modifier
            .fillMaxWidth()
            .saveOnFocusChange { viewModel.saveModel(local.model) },
        singleLine = true,
        placeholder = { Text("deepseek-v4-flash") }
    )

    Spacer(Modifier.height(8.dp))

    OutlinedButton(
        onClick = {
            // 先落盘当前编辑中的 Key/URL，再拉列表
            viewModel.saveApiKey(local.apiKey)
            viewModel.saveApiUrl(local.apiUrl)
            viewModel.fetchModels(local.apiKey, local.apiUrl)
        },
        enabled = !state.isLoadingModels,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (state.isLoadingModels) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
            Text("获取中…")
        } else {
            Text("获取模型列表")
        }
    }

    state.modelsError?.let { err ->
        Spacer(Modifier.height(6.dp))
        Text(
            text = err,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.dismissModelsError() }
        )
    }

    if (state.availableModels.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "共 ${state.availableModels.size} 个模型，点选填入上方",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_FAINT_TEXT)
        )
        Spacer(Modifier.height(4.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ALPHA_CARD_BG_MID)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(state.availableModels, key = { it }) { modelId ->
                    val selected = modelId == local.model
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                local.model = modelId
                                viewModel.selectModel(modelId)
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = modelId,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "已选中",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = ALPHA_DIVIDER)
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    Text(
        text = "Temperature: ${"%.2f".format(local.temperature)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        value = local.temperature,
        onValueChange = { local.temperature = it },
        onValueChangeFinished = {
            viewModel.updateTemperature(local.temperature.toDouble())
        },
        valueRange = TEMPERATURE_RANGE,
        steps = TEMPERATURE_STEPS,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(inactiveTrackColor = sliderTrackColor(darkTheme))
    )

    Spacer(Modifier.height(8.dp))

    Text(
        text = "最大 Token: ${local.maxTokens.toInt()}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        value = local.maxTokens,
        onValueChange = { local.maxTokens = it },
        onValueChangeFinished = {
            viewModel.updateMaxTokens(local.maxTokens.toInt())
        },
        valueRange = MAX_TOKENS_RANGE,
        steps = MAX_TOKENS_STEPS,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(inactiveTrackColor = sliderTrackColor(darkTheme))
    )
}

/** System Prompt 编辑区。 */
@Composable
private fun SystemPromptSection(
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
private fun MemorySection(
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

/** 背景壁纸：当前预览 + 从相册选图 + 恢复默认 + 模糊强度。 */
@Composable
private fun BackgroundWallpaperSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    darkTheme: Boolean
) {
    SectionTitle("背景壁纸")
    Text(
        text = "从相册选一张图片作为主界面聊天背景，实时生效；悬浮窗保持透明",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT),
        modifier = Modifier.padding(bottom = 8.dp)
    )

    // 相册选图（PickVisualMedia，免权限；activity-compose 已内置）
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.importWallpaper(it) }
    }

    // ── 模糊强度（本地拖动状态）──
    // 仅在有壁纸时启用。用本地 remember 拖动顺滑，松手才落盘（与语速/透明度一致）。
    // 先于预览声明，让预览在拖动时实时跟随。
    val blur = state.wallpaperBlur.toFloat().coerceIn(0f, 1f)
    var localBlur by remember { mutableStateOf(blur) }
    LaunchedEffect(state.wallpaperBlur) {
        if (localBlur != state.wallpaperBlur.toFloat()) {
            localBlur = state.wallpaperBlur.toFloat()
        }
    }

    // 源缩略图（未模糊）：路径不变就复用，避免随滑杆拖动反复解码原图
    var sourceThumb by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(state.wallpaperPath) {
        sourceThumb = if (state.wallpaperPath.isBlank()) null
        else withContext(Dispatchers.IO) { decodeThumbnail(state.wallpaperPath, 480) }
    }

    // 实时预览：模糊强度（localBlur）变化即在 IO 线程重算；0 = 直接显示源图。
    // σ 与 GL 渲染同源：blur→σ = 12·√blur（见 Live2dDefine.blurToSigma），再按
    // 「缩略图宽 / 屏幕宽」等比换算，保证预览模糊观感与主界面一致。
    val screenWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val preview by produceState<Bitmap?>(initialValue = sourceThumb, sourceThumb, localBlur) {
        val src = sourceThumb
        value = if (src == null || localBlur <= 0.001f) src
        else withContext(Dispatchers.IO) {
            val sigma = com.meapet.mobile.live2d.Live2dDefine.blurToSigma(localBlur) *
                src.width / screenWidthPx
            gaussianBlurBitmap(src, sigma)
        }
    }

    // 预览盒纵横比（宽 / 高）：竖图源也按盒子比例做 cover 裁剪，不溢出盒高、不叠到下方按钮。
    // 竖图放大到宽度填满盒宽后高度远超盒高，需按比例约束（aspectRatio 锁形状），
    // 再让 Image 在盒内居中铺满并裁掉超出的上/下部分。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ALPHA_CARD_BG)),
        contentAlignment = Alignment.Center
    ) {
        val bmp = preview
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "当前壁纸",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(PREVIEW_ASPECT_RATIO)
                    .align(Alignment.Center),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = "当前为默认纯色背景",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT)
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                pickMedia.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            modifier = Modifier.weight(1f)
        ) {
            Text("从相册选择")
        }
        OutlinedButton(
            onClick = { viewModel.clearWallpaper() },
            enabled = state.wallpaperPath.isNotBlank(),
            modifier = Modifier.weight(1f)
        ) {
            Text("恢复默认")
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = "背景模糊: ${kotlin.math.round(localBlur * 100).toInt()}%",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
            alpha = if (state.wallpaperPath.isNotBlank()) 1f else 0.5f
        )
    )
    Slider(
        value = localBlur,
        onValueChange = { localBlur = it },
        onValueChangeFinished = {
            viewModel.updateWallpaperBlur(localBlur.toDouble())
        },
        valueRange = WALLPAPER_BLUR_RANGE,
        steps = WALLPAPER_BLUR_STEPS,
        enabled = state.wallpaperPath.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(inactiveTrackColor = sliderTrackColor(darkTheme))
    )
}

/** 按 maxDim 限制采样解码缩略图。 */
private fun decodeThumbnail(path: String, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    })
}

/**
 * 可分离高斯模糊（CPU，两遍同轴，约 3σ 截断）。
 *
 * 仅在 IO 线程调用（随滑杆拖动时也会触发，保持 480px 缩略图尺度、单遍 O(n) 成本）。
 * 内核 tap 数随 σ 自适应（与 GL 33-tap / σ≤16px 的口径一致）；σ≤0.6 时截断半径不足
 * 1 像素，直接返回原图避免画质损失与多余开销。
 */
private fun gaussianBlurBitmap(src: Bitmap, sigma: Float): Bitmap {
    val w = src.width
    val h = src.height
    if (sigma <= 0.6f) return src

    val sigmaC = sigma.coerceIn(0.6f, 16f)
    val radius = (sigmaC * 3f).toInt().coerceIn(1, 33)
    val half = radius.coerceAtMost(33)

    // 预计算 (2*half+1)-tap 高斯权重（对称），并归一化
    val weights = FloatArray(2 * half + 1)
    var sum = 0f
    val twoSigma2 = 2f * sigmaC * sigmaC
    for (i in -half..half) {
        val wgt = kotlin.math.exp(-(i * i) / twoSigma2)
        weights[i + half] = wgt
        sum += wgt
    }
    for (i in weights.indices) weights[i] /= sum

    val srcPx = IntArray(w * h)
    src.getPixels(srcPx, 0, w, 0, 0, w, h)

    // 第 1 遍：水平模糊
    val tmp = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var r = 0f; var g = 0f; var b = 0f; var a = 0f
            for (i in -half..half) {
                val px = srcPx[row + (x + i).coerceIn(0, w - 1)]
                val wt = weights[i + half]
                a += ((px ushr 24) and 0xFF) * wt
                r += ((px ushr 16) and 0xFF) * wt
                g += ((px ushr 8) and 0xFF) * wt
                b += (px and 0xFF) * wt
            }
            tmp[row + x] = ((a.toInt() and 0xFF) shl 24) or
                ((r.toInt() and 0xFF) shl 16) or
                ((g.toInt() and 0xFF) shl 8) or
                (b.toInt() and 0xFF)
        }
    }

    // 第 2 遍：垂直模糊，直接写入结果
    val outPx = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var r = 0f; var g = 0f; var b = 0f; var a = 0f
            for (i in -half..half) {
                val px = tmp[(y + i).coerceIn(0, h - 1) * w + x]
                val wt = weights[i + half]
                a += ((px ushr 24) and 0xFF) * wt
                r += ((px ushr 16) and 0xFF) * wt
                g += ((px ushr 8) and 0xFF) * wt
                b += (px and 0xFF) * wt
            }
            outPx[y * w + x] = ((a.toInt() and 0xFF) shl 24) or
                ((r.toInt() and 0xFF) shl 16) or
                ((g.toInt() and 0xFF) shl 8) or
                (b.toInt() and 0xFF)
        }
    }

    return Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)
}

/** 外观：背景壁纸 + 聊天气泡透明度 + 主题（模式 / 动态颜色 / 颜色预设）。 */
@Composable
private fun AppearanceSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    darkTheme: Boolean
) {
    // ── 背景壁纸 ──
    BackgroundWallpaperSection(state, viewModel, darkTheme)

    Spacer(Modifier.height(12.dp))

    // ── 聊天气泡 ──
    // 透明度滑杆（0.2~1.0）。用本地 remember 拖动顺滑，松手才落盘（与语速一致）。
    val alpha = state.chatBubbleAlpha.toFloat().coerceIn(0.2f, 1.0f)
    var localAlpha by remember { mutableStateOf(alpha) }
    LaunchedEffect(state.chatBubbleAlpha) {
        if (localAlpha != state.chatBubbleAlpha.toFloat()) {
            localAlpha = state.chatBubbleAlpha.toFloat()
        }
    }

    Text(
        text = "聊天气泡透明度: ${(localAlpha * 100).toInt()}%",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        value = localAlpha,
        onValueChange = { localAlpha = it },
        onValueChangeFinished = {
            viewModel.updateChatBubbleAlpha(localAlpha.toDouble())
        },
        valueRange = CHAT_BUBBLE_ALPHA_RANGE,
        steps = CHAT_BUBBLE_ALPHA_STEPS,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(inactiveTrackColor = sliderTrackColor(darkTheme))
    )

    Spacer(Modifier.height(12.dp))
    SectionTitle("主题")

    // ── 主题模式 ──
    ThemeModeSelector(
        current = state.themeMode,
        onSelect = { viewModel.updateThemeMode(it) }
    )

    Spacer(Modifier.height(12.dp))

    // ── 动态颜色开关 ──
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    SettingsSwitchRow(
        label = "使用系统动态颜色",
        description = if (dynamicColorSupported) "关闭后可选择预设主题色" else "当前系统不支持动态颜色",
        checked = state.enableDynamicColor && dynamicColorSupported,
        darkTheme = darkTheme,
        onCheckedChange = { if (dynamicColorSupported) viewModel.updateEnableDynamicColor(it) },
        enabled = dynamicColorSupported
    )

    // ── 颜色预设选择区（关闭动态颜色时展开） ──
    AnimatedVisibility(
        visible = !(state.enableDynamicColor && dynamicColorSupported),
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        ColorPresetSelector(
            currentPreset = state.colorPreset,
            onSelect = { viewModel.updateColorPreset(it) }
        )
    }
}

/** 语音：模型下载管理 + 主/悬浮窗开关 + 默认语音 + 语速。 */
@Composable
private fun TtsSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    darkTheme: Boolean
) {
    val modelReady = state.ttsModelState is com.meapet.mobile.tts.model.TtsModelState.Ready

    // ── 语音模型管理 ──
    TtsModelCard(state, viewModel)

    Spacer(Modifier.height(8.dp))
    SectionTitle("发声")

    // ── 发声开关（模型未就绪时置灰）──
    SettingsSwitchRow(
        label = "主界面语音",
        description = if (modelReady) "对话回复在主界面朗读" else "需先下载语音模型",
        checked = state.ttsMainEnabled && modelReady,
        darkTheme = darkTheme,
        onCheckedChange = { viewModel.updateTtsMainEnabled(it) },
        enabled = modelReady
    )
    SettingsSwitchRow(
        label = "悬浮窗语音",
        description = if (modelReady) "悬浮窗回复朗读" else "需先下载语音模型",
        checked = state.ttsOverlayEnabled && modelReady,
        darkTheme = darkTheme,
        onCheckedChange = { viewModel.updateTtsOverlayEnabled(it) },
        enabled = modelReady
    )

    // ── 语速 ──
    // 内部存的是 length_scale（<1 快、>1 慢）；滑杆显示/拖动的是语速倍率 speed = 1/length_scale
    // （0.5=半速、1.0=原速、2.0=双倍速），方向符合直觉。
    // 用本地 remember 状态拖动（顺滑，不随 DataStore 流回环），松手才落盘。
    val lengthScale = state.ttsLengthScale.toFloat().coerceIn(0.5f, 2.0f)
    var speed by remember {
        mutableStateOf((1f / lengthScale).coerceIn(0.5f, 2.0f))
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "语速: ${"%.2f".format(speed)}x",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (modelReady) 1f else 0.5f)
    )
    Slider(
        value = speed,
        onValueChange = { speed = it },
        onValueChangeFinished = {
            // speed（0.5..2.0）→ length_scale = 1/speed；取整到 0.01，避免 1/0.6=1.6667 这类
            // 非 0.1 对齐值，保证下次进页面显示不回跳。
            val lengthScale = kotlin.math.round((1f / speed) * 100) / 100.0
            viewModel.updateTtsLengthScale(lengthScale)
        },
        valueRange = TTS_SPEED_RANGE,
        steps = TTS_SPEED_STEPS,
        enabled = modelReady,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(inactiveTrackColor = sliderTrackColor(darkTheme))
    )
}

/** 语音模型下载状态卡：状态显示 + 下载/导入/进度/删除。 */
@Composable
private fun TtsModelCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    // 从本地 zip 资源包手动导入（绕过 GitHub 下载）
    val pickZip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importTtsModelZip(it) }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            when (val st = state.ttsModelState) {
                is com.meapet.mobile.tts.model.TtsModelState.Ready -> {
                    Text("语音模型已就绪", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "模型与运行库已下载，语音功能可用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { viewModel.deleteTtsModel() }) {
                        Text("删除模型")
                    }
                }
                is com.meapet.mobile.tts.model.TtsModelState.Downloading -> {
                    Text("正在下载语音模型…", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        st.currentFile,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressLike(progress = st.progress)
                }
                is com.meapet.mobile.tts.model.TtsModelState.Importing -> {
                    Text("正在导入语音模型…", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "正在解压资源包，请稍候",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                is com.meapet.mobile.tts.model.TtsModelState.Error -> {
                    Text("下载失败", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        st.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.downloadTtsModel() }) { Text("重试") }
                        OutlinedButton(onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                            Text("从本地导入")
                        }
                    }
                }
                is com.meapet.mobile.tts.model.TtsModelState.NotDownloaded -> {
                    Text("语音模型未下载", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (state.ttsModelUrlConfigured)
                            "约 92MB（模型 + 运行库），下载后开放语音功能；网络受限可从本地 zip 导入"
                        else
                            "未配置模型下载地址，可从本地 zip 导入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.downloadTtsModel() },
                            enabled = state.ttsModelUrlConfigured
                        ) { Text("下载模型") }
                        OutlinedButton(
                            onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream")) }
                        ) { Text("从本地导入") }
                    }
                }
            }
        }
    }
}

/** 轻量进度条（包一层避免引入额外 import 差异）。 */
@Composable
private fun LinearProgressLike(progress: Float) {
    androidx.compose.material3.LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth()
    )
}

/** 轻量选择片（替代 FilterChip，保持现有视觉风格）。 */
@Composable
private fun FilterChipLike(
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

/** 更新：启动自动检查开关。 */
@Composable
private fun UpdateSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    darkTheme: Boolean
) {
    SettingsSwitchRow(
        label = "启动时自动检查更新",
        description = "打开 App 时静默检测新版本，发现更新才会提示；关闭后仅可在关于页手动检查",
        checked = state.enableAutoUpdateCheck,
        darkTheme = darkTheme,
        onCheckedChange = { viewModel.updateEnableAutoUpdateCheck(it) }
    )
}

/** 隐私与数据：查看隐私政策 + 友盟采集授权管理。 */
@Composable
private fun PrivacySection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenPrivacyPolicy: () -> Unit,
    onExitApp: () -> Unit
) {
    // 响应式读取授权状态（由 SettingsViewModel 订阅 PrivacyConsentManager.agreedFlow 维护）
    val umengAgreed = state.privacyAgreed
    var showRevokeDialog by remember { mutableStateOf(false) }

    // 查看隐私协议
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenPrivacyPolicy() },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "查看隐私政策",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = 180f }
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    // 导出日志（拉起系统分享，发给开发者排查问题）
    val exportingLog by viewModel.exportingLog.collectAsState()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !exportingLog) { viewModel.exportLog() },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (exportingLog) "正在导出日志…" else "导出日志",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (exportingLog) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = 180f }
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "统计数据采集",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (umengAgreed)
                    "已授权：友盟统计 SDK 正在采集去标识化的使用数据"
                else
                    "未授权：不会采集任何统计数据，App 正常使用",
                style = MaterialTheme.typography.bodySmall,
                color = if (umengAgreed)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT),
                modifier = Modifier.padding(top = 4.dp)
            )
            if (umengAgreed) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showRevokeDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消数据采集授权")
                }
            }
        }
    }

    if (showRevokeDialog) {
        AlertDialog(
            onDismissRequest = { showRevokeDialog = false },
            title = { Text("取消数据采集授权") },
            text = {
                Text(
                    "为确保撤回后立即、彻底停止数据采集，取消授权后 App 将自动退出；重新打开即可正常使用，且不会再进行任何统计采集。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.revokePrivacyConsent()
                    showRevokeDialog = false
                    onExitApp()
                }) {
                    Text("确认取消并退出")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeDialog = false }) {
                    Text("保留授权")
                }
            }
        )
    }
}

// ═══════════════════════════════════════════════════
//  通用子组件
// ═══════════════════════════════════════════════════

/**
 * 设置分组卡片：带组标题的圆角 Surface 容器。
 *
 * 设置界面按功能域分组（对话 / 外观 / 语音 / 更新 / 隐私与数据），
 * 组内由各 Section 组件用 [SectionTitle] 细分小节。
 */
@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SettingsSwitchRow(
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

@Composable
private fun ThemeModeSelector(
    current: String,
    onSelect: (String) -> Unit
) {
    val options = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
    var expanded by remember { mutableStateOf(false) }
    var showPopup by remember { mutableStateOf(false) }
    var boxWidthPx by remember { mutableStateOf(0) }
    var boxHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(expanded) {
        if (expanded) {
            showPopup = true
            animProgress.animateTo(1f, animationSpec = tween(200))
        } else if (showPopup) {
            animProgress.animateTo(0f, animationSpec = tween(200))
            showPopup = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                boxWidthPx = it.size.width
                boxHeightPx = it.size.height
            }
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == current }?.second ?: "跟随系统",
            onValueChange = {},
            readOnly = true,
            label = { Text("主题模式") },
            trailingIcon = {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                                  else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        // 透明点击层——避免与 OutlinedTextField 的内部触摸处理冲突
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = !expanded }
        )

        if (showPopup) {
            val popupWidth = with(density) { boxWidthPx.toDp().coerceAtLeast(160.dp) }
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(x = 0, y = boxHeightPx + 4),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true)
            ) {
                Surface(
                    modifier = Modifier
                        .width(popupWidth)
                        .graphicsLayer {
                            alpha = animProgress.value
                            scaleX = 0.95f + 0.05f * animProgress.value
                            scaleY = 0.95f + 0.05f * animProgress.value
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp
                ) {
                    Column {
                        options.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { onSelect(value); expanded = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 颜色预设选择区——色块网格。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPresetSelector(
    currentPreset: String,
    onSelect: (String) -> Unit
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            "主题色预设",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            THEME_PRESETS.forEach { preset ->
                val isSelected = preset.id == currentPreset

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onSelect(preset.id) }
                        .width(56.dp)
                ) {
                    // 色块圆
                    val borderMod = if (isSelected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape)
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(preset.seed)
                            .then(borderMod)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
