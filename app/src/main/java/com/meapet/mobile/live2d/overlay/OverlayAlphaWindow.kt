package com.meapet.mobile.live2d.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.meapet.mobile.R
import kotlin.math.roundToInt

/**
 * 悬浮窗透明度调节面板（独立小悬浮窗）。
 *
 * 菜单点击「透明度」后出现在人物悬浮窗侧面，内嵌一条 Material3 风格滑杆
 * （圆头拇指 + 圆角轨道，与设置页观感一致），拖动即实时回调 [onAlphaChanged]
 * 调整 Live2D 悬浮窗透明度（View.alpha）。右上角有关闭按钮。
 *
 * 为避免用户把透明度拉到 0 后找不到人物，滑杆范围被钳制在
 * [MIN_ALPHA]..1.0（[MIN_ALPHA] 之上仍依稀可见，便于找回）。
 * 无操作 [AUTO_HIDE_MS] 后自动隐藏。
 *
 * @param context 上下文（Service 即可）
 * @param currentAlpha 当前透明度（滑杆初始位置）
 * @param onAlphaChanged 透明度实时变化回调（0..1，已钳制下限）
 */
@SuppressLint("ViewConstructor")
class OverlayAlphaWindow(
    context: Context,
    currentAlpha: Float,
    private val onAlphaChanged: (Float) -> Unit,
) {
    companion object {
        private const val TAG = "OverlayAlphaWindow"

        /** 无操作多少毫秒后自动隐藏。 */
        private const val AUTO_HIDE_MS = 6000L

        /** 透明度下限（防止用户调到 0 找不到人物）。 */
        const val MIN_ALPHA = 0.2f

        /** 与人物悬浮窗之间的间距，dp。 */
        private const val GAP_DP = 4f

        /** 面板圆角半径，dp。 */
        private const val CORNER_RADIUS_DP = 16f

        /** 面板宽度，dp。 */
        private const val PANEL_WIDTH_DP = 210f

        /** 滑杆步进数（0..STEPS 映射 MIN_ALPHA..1.0）。 */
        private const val STEPS = 100
    }

    private val ctx: Context = context
    private val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private val palette = OverlayPalette.resolve(ctx)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var anchor: Rect? = null
    private var showing = false
    private val autoHideRunnable = Runnable { hide() }

    private val rootView: LinearLayout
    private val valueLabel: TextView
    private val seekBar: SeekBar
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // 不抢焦点但可点击拖动滑杆；窗外交互仍可穿透给下层
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    init {
        valueLabel = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(palette.onSurface)
        }

        // Material3 风格滑杆：圆形拇指 + 圆角轨道，激活段主题色、未激活段半透明
        seekBar = SeekBar(ctx).apply {
            max = STEPS
            progress = alphaToProgress(currentAlpha)
            thumb = ContextCompat.getDrawable(ctx, R.drawable.ic_slider_thumb)
            progressDrawable = ContextCompat.getDrawable(ctx, R.drawable.ic_slider_track)
            // 拇指与激活轨道染主题色，未激活轨道半透明白/灰由 drawable 自带
            thumbTintList = ColorStateList.valueOf(palette.primary)
            progressTintList = ColorStateList.valueOf(palette.primary)
            // 去掉 SeekBar 默认左右内边距造成的拇指截断
            setPadding(0, 0, 0, 0)
            splitTrack = false
        }

        // 关闭按钮（右上角 ×，MDI close 图标）
        val closeButton = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(palette.onSurface)
            val pad = dp(4)
            setPadding(pad, pad, pad, pad)
            // 按压水波纹
            val outValue = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { hide() }
        }

        // 标题行：左侧百分比文字，右侧关闭按钮
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(valueLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(closeButton, LinearLayout.LayoutParams(dp(28), dp(28)))
        }

        rootView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(palette.surfaceVariant.withAlpha(0xF2))
                cornerRadius = dp(CORNER_RADIUS_DP).toFloat()
                setStroke(dp(1), palette.onSurface.withAlpha(0x1A))
            }
            elevation = 8f * density
            setPadding(dp(14), dp(8), dp(10), dp(10))
            addView(header, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(seekBar, LinearLayout.LayoutParams(
                dp(PANEL_WIDTH_DP),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        updateLabel(progressToAlpha(seekBar.progress))
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val alpha = progressToAlpha(progress)
                updateLabel(alpha)
                if (fromUser) {
                    onAlphaChanged(alpha)
                    resetAutoHide()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) = resetAutoHide()
            override fun onStopTrackingTouch(sb: SeekBar) = resetAutoHide()
        })
        // 触摸面板任意处重置自动隐藏定时器（返回 false 让事件继续传给滑杆）
        rootView.setOnTouchListener { _, _ -> resetAutoHide(); false }
        rootView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
    }

    val isVisible: Boolean get() = rootView.isAttachedToWindow

    /** 在人物悬浮窗侧面显示透明度面板并启动自动隐藏计时。 */
    fun show(anchorRect: Rect) {
        anchor = anchorRect
        showing = true
        if (!isVisible) {
            rootView.alpha = 0f
            try { windowManager.addView(rootView, params) } catch (_: Exception) {}
        }
        place()
        rootView.post {
            if (showing && rootView.isAttachedToWindow) {
                rootView.animate().cancel()
                rootView.animate().alpha(1f).setDuration(180).start()
            }
        }
        resetAutoHide()
    }

    /** 隐藏面板：淡出后移除窗口。 */
    fun hide() {
        mainHandler.removeCallbacks(autoHideRunnable)
        showing = false
        if (isVisible) {
            rootView.animate().cancel()
            rootView.animate().alpha(0f).setDuration(180).withEndAction {
                if (rootView.isAttachedToWindow) {
                    try { windowManager.removeView(rootView) } catch (_: Exception) {}
                }
            }.start()
        }
    }

    /** 人物悬浮窗移动后跟随定位。 */
    fun reposition(anchorRect: Rect) {
        anchor = anchorRect
        if (isVisible) place()
    }

    /** 释放窗口（等同 hide 并清除锚点）。 */
    fun destroy() {
        anchor = null
        hide()
    }

    // ================ 定位 ================

    private fun place() {
        val a = anchor ?: return
        val w = rootView.width
        val h = rootView.height
        if (w <= 0 || h <= 0) return
        val dm = rootView.resources.displayMetrics
        val gap = dp(GAP_DP)
        // 与菜单同一侧（人物偏左→面板在右），垂直对齐人物中心
        val sideRight = a.centerX() < dm.widthPixels / 2
        params.x = if (sideRight) a.right + gap else a.left - w - gap
        params.x = params.x.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
        params.y = (a.centerY() - h / 2).coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
        try { windowManager.updateViewLayout(rootView, params) } catch (_: Exception) {}
    }

    private fun resetAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, AUTO_HIDE_MS)
    }

    // ================ 数值映射 ================

    /** 透明度 → 滑杆进度（MIN_ALPHA..1.0 映射 0..STEPS）。 */
    private fun alphaToProgress(alpha: Float): Int =
        (((alpha.coerceIn(MIN_ALPHA, 1f) - MIN_ALPHA) / (1f - MIN_ALPHA)) * STEPS).roundToInt()

    /** 滑杆进度 → 透明度。 */
    private fun progressToAlpha(progress: Int): Float =
        MIN_ALPHA + (progress.toFloat() / STEPS) * (1f - MIN_ALPHA)

    private fun updateLabel(alpha: Float) {
        valueLabel.text = "透明度 ${(alpha * 100).roundToInt()}%"
    }

    private fun dp(v: Number): Int = (v.toFloat() * density).roundToInt()

    /** 替换颜色最高位 alpha 通道。 */
    private fun Int.withAlpha(a: Int): Int = this and 0x00FFFFFF or (a shl 24)
}
