package com.meapet.mobile.ui.screen.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meapet.mobile.R

/**
 * 初次使用引导：欢迎 → 填写 API Key → 结束。
 *
 * 视觉沿用设置页规范（半透明卡片 / 16dp 圆角 / 主题色按钮），背景为半透明遮罩，
 * 无下层毛玻璃时即退化为主题色，与全应用观感一致。
 *
 * @param initialApiKey 已保存的 Key（重新引导时预填）
 * @param onFinish 引导结束回调，携带用户填写的 Key（可为空 = 跳过）
 */
@Composable
fun OnboardingScreen(
    initialApiKey: String = "",
    onFinish: (apiKey: String) -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    var apiKey by remember { mutableStateOf(initialApiKey) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 不透明背景（跟随主题）：引导期间遮住下层，避免内容重影。
            // 注意：这里不再全局消费触摸事件——那会干扰子组件（按钮）的点击判定，
            // 导致"第一次点击无反应"。下层交互改由「引导期间不组合聊天界面」根治（见 MainActivity）。
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            // ── 主体内容（随步骤切换）──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val forward = targetState > initialState
                        if (forward) {
                            (slideInHorizontally { w -> w } + fadeIn())
                                .togetherWith(slideOutHorizontally { w -> -w / 3 } + fadeOut())
                        } else {
                            (slideInHorizontally { w -> -w } + fadeIn())
                                .togetherWith(slideOutHorizontally { w -> w / 3 } + fadeOut())
                        }
                    },
                    label = "onboardingStep"
                ) { current ->
                    when (current) {
                        STEP_WELCOME -> WelcomePage()
                        STEP_API_KEY -> ApiKeyPage(
                            apiKey = apiKey,
                            onApiKeyChange = { apiKey = it }
                        )
                        else -> FinishPage()
                    }
                }
            }

            // ── 步骤指示器 ──
            StepIndicator(
                current = step,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 16.dp)
            )

            // ── 底部按钮 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (step) {
                    STEP_WELCOME -> {
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { step = STEP_API_KEY }) { Text("开始设置") }
                    }
                    STEP_API_KEY -> {
                        // 可跳过：没有 Key 也能先进应用（本地模型无需 Key）
                        TextButton(onClick = { step = STEP_FINISH }) { Text("稍后设置") }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = { step = STEP_FINISH },
                            enabled = apiKey.isNotBlank()
                        ) { Text("下一步") }
                    }
                    else -> {
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { onFinish(apiKey) }) { Text("开始使用") }
                    }
                }
            }
        }
    }
}

// ── 页面 1：欢迎 ─────────────────────────────────────

@Composable
private fun WelcomePage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            // 图标本身是方形资源，裁圆角以融入整体观感
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(32.dp))
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "梅尔桌宠",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "和梅尔聊天，看她动起来。\n先花一分钟完成设置吧。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ── 页面 2：填写 API Key ─────────────────────────────

@Composable
private fun ApiKeyPage(
    apiKey: String,
    onApiKeyChange: (String) -> Unit
) {
    val context = LocalContext.current
    var keyVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "填写 API Key",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "梅尔需要连接大语言模型才能对话，\n填入你的 API Key 即可开始。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text("API Key") },
            placeholder = { Text("sk-...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(
                    onClick = { keyVisible = !keyVisible },
                    modifier = Modifier.width(56.dp)
                ) {
                    Text(
                        text = if (keyVisible) "隐藏" else "显示",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        )

        Spacer(Modifier.height(16.dp))
        ApiKeyHelpCard(context = context)
    }
}

/** 获取 Key 的指引卡片：两个平台（可跳浏览器）+ QQ 反馈群（点击复制）。 */
@Composable
private fun ApiKeyHelpCard(context: Context) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ONBOARDING_CARD_ALPHA),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "还没有 API Key？",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))

            PlatformLinkRow(
                name = "DeepSeek 开放平台",
                tag = "推荐 · 付费",
                description = "效果稳定，适合日常使用",
                url = DEEPSEEK_URL,
                context = context
            )
            Spacer(Modifier.height(10.dp))
            PlatformLinkRow(
                name = "NVIDIA",
                tag = "免费额度",
                description = "提供免费 API，适合先体验",
                url = NVIDIA_URL,
                context = context
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = "遇到问题？加入 QQ 反馈群（点击复制群号）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { copyQqGroup(context) },
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "QQ 群：$QQ_GROUP",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** 单条平台指引：名称 + 标签 + 说明 + 可点击链接。 */
@Composable
private fun PlatformLinkRow(
    name: String,
    tag: String,
    description: String,
    url: String,
    context: Context
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { openUrl(context, url) },
        color = MaterialTheme.colorScheme.surface.copy(alpha = ONBOARDING_CARD_ALPHA),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ── 页面 3：结束 ─────────────────────────────────────

@Composable
private fun FinishPage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "准备就绪！",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "之后可以在「设置 → 提供商」里\n随时修改 API Key 与模型。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ── 步骤指示器 ───────────────────────────────────────

@Composable
private fun StepIndicator(current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(STEP_COUNT) { index ->
            val selected = index == current
            Box(
                modifier = Modifier
                    .size(if (selected) 9.dp else 7.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
            )
        }
    }
}

// ── 工具函数 ─────────────────────────────────────────

/** 用系统浏览器打开链接（无可用应用时提示而不是崩溃）。 */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        Toast.makeText(context, "没有可打开链接的应用", Toast.LENGTH_SHORT).show()
    }
}

/** 复制 QQ 群号到剪贴板（兼容无 QQ 的设备）。 */
private fun copyQqGroup(context: Context) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("QQ 群", QQ_GROUP))
        Toast.makeText(context, "QQ 群号已复制：$QQ_GROUP", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, "复制失败，QQ 群号：$QQ_GROUP", Toast.LENGTH_SHORT).show()
    }
}

// ── 常量 ────────────────────────────────────────────

/** 引导页卡片 alpha（与设置页卡片一致）。 */
private const val ONBOARDING_CARD_ALPHA = 0.3f

private const val STEP_WELCOME = 0
private const val STEP_API_KEY = 1
private const val STEP_FINISH = 2
private const val STEP_COUNT = 3

private const val DEEPSEEK_URL = "https://platform.deepseek.com"
private const val NVIDIA_URL = "https://build.nvidia.com"
private const val QQ_GROUP = "1083503687"
