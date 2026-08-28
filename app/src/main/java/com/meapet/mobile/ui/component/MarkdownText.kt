package com.meapet.mobile.ui.component

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import ru.noties.jlatexmath.JLatexMathAndroid
import org.scilab.forge.jlatexmath.TeXFormula
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * LaTeX 初始化（一次性）。
 *
 * jlatexmath-android 的符号表 TeXSymbols.xml（\infty \sum \alpha 等 621 个符号）
 * 在 JLatexMathAndroid.init(context) 之后才会在类初始化时从 assets 自动加载。
 * Markwon 插件自身不调 init（库虽有自动初始化 Provider，为防 manifest 合并失效，
 * 这里显式调用并预热）。
 */
@Volatile
private var latexInitialized = false
private val latexInitLock = Any()

private fun ensureLatexInit(context: Context) {
    if (latexInitialized) return
    synchronized(latexInitLock) {
        if (latexInitialized) return
        JLatexMathAndroid.init(context.applicationContext)
        try {
            // 预热：触发 TeXFormula/SymbolAtom 静态初始化（加载符号表 + 字体配置）
            TeXFormula("x")
        } catch (t: Throwable) {
            Log.w("MarkdownText", "LaTeX 预热失败，公式可能无法渲染", t)
        }
        latexInitialized = true
    }
}

/**
 * 定界符归一化。
 *
 * Markwon 4.6.2 的 LaTeX 定界符统一是 $$...$$（行内正则 (\${2})([\s\S]+?)\1，
 * 块级也是 $$），**单个 $ 根本不被识别**。而 AI 模型常输出四种写法：
 *   \[...\]（显示公式）、\(...\)（行内）、$$...$$、$...$
 * 这里统一收敛到 $$...$$，否则整段公式按原文显示（上个版本就死在把 \(...\) 转成单 $）。
 */
internal fun normalizeLatexDelimiters(src: String): String {
    var s = src
    // \[ ... \] 显示公式 → $$ ... $$
    s = Regex("""\\\[([\s\S]+?)\\\]""").replace(s) { "\$\$${it.groupValues[1]}\$\$" }
    // \( ... \) 行内公式 → $$ ... $$
    s = Regex("""\\\(([\s\S]+?)\\\)""").replace(s) { "\$\$${it.groupValues[1]}\$\$" }
    // 单美元行内 $ ... $ → $$ ... $$。仅当内容含 LaTeX 信号（\ ^ _ { }）才转，
    // 避免把 "价格 $5 和 $10" 这类货币成对误判为公式。
    s = Regex("""(?<![\\$])\$(?!\$)([^\n$]*[\\^_{}][^\n$]*)(?<![\\$])\$(?!\$)""")
        .replace(s) { "\$\$${it.groupValues[1]}\$\$" }
    return s
}

/**
 * Markdown 渲染工厂。
 *
 * Markwon 实例按暗色模式缓存两份（公式字体等插件初始化开销较大，避免逐条消息重建）。
 * 颜色不进缓存键：文本/代码背景在 TextView 层逐帧设置，跟随主题与气泡透明度。
 */
private val markwonCache = ConcurrentHashMap<Boolean, Markwon>()

private fun obtainMarkwon(context: Context, dark: Boolean, textSizePx: Float, tableBorder: Int): Markwon =
    markwonCache.getOrPut(dark) {
        Markwon.builder(context)
            .usePlugin(CorePlugin.create())
            // 行内解析器：JLatexMathPlugin 的行内公式处理器需注册到它上面
            .usePlugin(MarkwonInlineParserPlugin.create())
            // ~~删除线~~（GFM）
            .usePlugin(StrikethroughPlugin.create())
            // 表格（边框色按主题定，不随气泡透明度）
            .usePlugin(TablePlugin.create(
                TableTheme.Builder()
                    .tableBorderColor(tableBorder)
                    .build()
            ))
            // LaTeX 公式：块级默认开启；显式打开行内（Markwon 4.6.2 行内默认关闭）。
            // 行内与块级均用 $$...$$ 定界。
            .usePlugin(JLatexMathPlugin.create(textSizePx) { builder ->
                builder.inlinesEnabled(true)
                builder.blocksEnabled(true)
            })
            // 裸 URL 自动识别为链接
            .usePlugin(LinkifyPlugin.create())
            .build()
    }

/**
 * 流式输出时未闭合的 ``` 围栏自动补全，
 * 防止"半截代码块"在渲染器里闪烁成普通文本。
 */
internal fun closeUnclosedFences(src: String): String {
    val fenceCount = src.lineSequence().count { it.trimStart().startsWith("```") }
    return if (fenceCount % 2 == 1) "$src\n```" else src
}

/**
 * 助手消息气泡的 Markdown 渲染组件。
 *
 * 用 Markwon(TextView) 渲染：代码块（等宽+背景色）、行内/块级 LaTeX 公式、
 * 表格、删除线、链接可点。颜色/透明度在 update 阶段桥接 MaterialTheme，
 * 主题切换与气泡透明度滑杆实时生效。
 *
 * @param markdown 消息正文
 * @param color 正文颜色（调用方已按气泡透明度处理）
 * @param alpha 气泡透明度（影响代码块背景）
 * @param isStreaming 流式中：未闭合代码围栏自动补全
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color,
    alpha: Float = 1f,
    isStreaming: Boolean = false,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val textSizePx = with(density) { 14.sp.toPx() }
    val tableBorder = if (dark) 0xFF49454F.toInt() else 0xFFCAC4D0.toInt()
    // 首次渲染前初始化 LaTeX 符号表（幂等）
    remember { ensureLatexInit(context); true }
    val markwon = remember(dark) { obtainMarkwon(context, dark, textSizePx, tableBorder) }

    // 渲染前处理：定界符归一化 + 流式中未闭合代码围栏补全
    val safe = remember(markdown, isStreaming) {
        val normalized = normalizeLatexDelimiters(markdown)
        if (isStreaming) closeUnclosedFences(normalized) else normalized
    }

    // 代码块背景：主题基准色叠加气泡透明度（Markwon CodeBlockSpan 取 hint color）
    val codeBgBase = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    val codeBg = codeBgBase.copy(alpha = codeBgBase.alpha * alpha)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                // 原生文字选择：长按弹出系统选择菜单（复制 / 全选）。
                // 先开 selectable（系统会把 movementMethod 置为 ArrowKeyMovementMethod），
                // 再覆盖回 LinkMovementMethod——长按选择由 textIsSelectable 标志驱动，
                // 与 movementMethod 无关，因此选择与链接点击可以并存。
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
                typeface = Typeface.SANS_SERIF
                setLineSpacing(0f, 1.2f)
                // 选中高亮：半透明主题蓝，选择时可见（不再用全透明，否则选区看不见）
                setHighlightColor(0x553F51B5)
            }
        },
        update = { tv ->
            tv.setTextColor(color.copy(alpha = alpha).toArgb())
            tv.setHintTextColor(codeBg.toArgb())
            tv.setLinkTextColor(ColorStateList.valueOf(color.copy(alpha = alpha).toArgb()))
            markwon.setMarkdown(tv, safe)
        }
    )
}
