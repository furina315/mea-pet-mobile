package com.meapet.mobile.live2d.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.meapet.mobile.R
import kotlin.math.roundToInt

/**
 * 悬浮窗模式下的独立输入框悬浮窗。
 *
 * 菜单点击"唤起输入框"后出现在人物悬浮窗正下方（默认），可拖动到任意位置
 * （拖动抓手为左侧的 ≡）。紧凑小条，不是主界面那种全宽药丸输入栏。
 * 发送后保持打开，便于连续发送多条消息。
 * ⚠️ 弹软键盘依赖非 NOT_FOCUSABLE 的窗口 flag（见 [params]）。
 *
 * @param context 上下文（Service 即可）
 * @param onSend 发送回调（文本已 trim 且非空）
 * @param onClose 关闭回调（隐藏输入框）
 */
@SuppressLint("ViewConstructor")
class OverlayInputWindow(
    context: Context,
    private val onSend: (String) -> Unit,
    private val onClose: () -> Unit,
) {
    companion object {
        private const val TAG = "OverlayInputWindow"

        /** addView 后延迟请求焦点 + 弹键盘，等待窗口真正可交互。 */
        private const val FOCUS_DELAY_MS = 150L

        /** 输入占位符，与主界面一致。 */
        private const val PLACEHOLDER = "给 Mea 发个消息..."

        /** 输入条固定宽度，dp（紧凑但够输入）。 */
        private const val BAR_WIDTH_DP = 260f

        /** 圆角半径，dp。 */
        private const val CORNER_RADIUS_DP = 20f

        /** 发送按钮直径，dp（主页 40dp 减 4px ≈ 36dp，适配紧凑输入条）。 */
        private const val SEND_BUTTON_DP = 36

        /** 发送图标边长，dp（与主页一致）。 */
        private const val SEND_ICON_DP = 20

        /** 与人物正下方的间距，dp。 */
        private const val GAP_DP = 8f
    }

    private val ctx: Context = context
    private val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private val palette = OverlayPalette.resolve(ctx)
    private val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val editText: EditText
    private val sendButton: ImageButton
    private val loadingView: ProgressBar
    private val rootView: View

    private val params = WindowManager.LayoutParams(
        dp(BAR_WIDTH_DP),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // 不加 FLAG_NOT_FOCUSABLE：输入框需要获得焦点才能弹软键盘；
        // NOT_TOUCH_MODAL 保证输入条外部的点击仍穿透给底层应用
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    /** 是否正在等待回复（发送中）。 */
    var isSending: Boolean = false
        private set

    // ----- 拖动状态（左侧抓手拖动） -----
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0

    init {
        // 透明无下划线的输入框，紧凑单行
        editText = EditText(ctx).apply {
            setHint(PLACEHOLDER)
            setHintTextColor(palette.onSurfaceVariant.withAlpha(0x99))  // ≈ 60% 透明
            setTextColor(palette.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = null
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendIfPossible()
                    true
                } else {
                    false
                }
            }
            // 输入变化时刷新发送钮可用态（与主页的空输入置灰一致）
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = refreshSendButton()
            })
        }

        loadingView = ProgressBar(ctx).apply {
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(palette.primary)
        }

        // 圆形主色发送按钮（MDI send 图标）；尺寸/禁用态与主页 ChatInputBar 一致
        // 初始禁用态在 init 末尾统一刷新（editText/sendButton 均就绪后）
        sendButton = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_send)
            contentDescription = "发送"
            setOnClickListener { sendIfPossible() }
        }

        // 发送区：发送按钮 / 加载指示互换；发送图标约束为 20dp 居中（与主页一致）
        val sendContainer = FrameLayout(ctx).apply {
            addView(
                sendButton,
                FrameLayout.LayoutParams(dp(SEND_BUTTON_DP), dp(SEND_BUTTON_DP))
            )
            addView(
                loadingView,
                FrameLayout.LayoutParams(dp(SEND_BUTTON_DP), dp(SEND_BUTTON_DP), Gravity.CENTER)
            )
        }
        sendButton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        sendButton.setPadding(
            dp((SEND_BUTTON_DP - SEND_ICON_DP) / 2), dp((SEND_BUTTON_DP - SEND_ICON_DP) / 2),
            dp((SEND_BUTTON_DP - SEND_ICON_DP) / 2), dp((SEND_BUTTON_DP - SEND_ICON_DP) / 2)
        )

        // 关闭按钮（MDI close 图标）
        val closeButton = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(palette.onSurfaceVariant)
            contentDescription = "关闭"
            isClickable = true
            val outValue = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { onClose() }
        }

        // 左侧拖动抓手（MDI drag-vertical 图标）
        val grip = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_drag)
            setColorFilter(palette.onSurfaceVariant.withAlpha(0xA6))
            contentDescription = "拖动"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnTouchListener { _, event -> handleDrag(event) }
        }

        // 紧凑输入条：抓手 + 输入框 + 发送 + 关闭
        rootView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(palette.surfaceVariant.withAlpha(0xF2))
                cornerRadius = dp(CORNER_RADIUS_DP).toFloat()
                setStroke(dp(1), palette.onSurface.withAlpha(0x1A))
            }
            elevation = 8f * density
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(grip, LinearLayout.LayoutParams(dp(32), dp(40)))
            addView(editText, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(sendContainer, LinearLayout.LayoutParams(dp(SEND_BUTTON_DP), dp(SEND_BUTTON_DP)))
            addView(closeButton, LinearLayout.LayoutParams(dp(32), dp(36)))
        }

        // 初始禁用态刷新（此刻 editText 与 sendButton 均已就绪）
        refreshSendButton()
    }

    /** 是否已显示在屏幕上。 */
    val isVisible: Boolean get() = rootView.isAttachedToWindow

    /** 显示输入框：默认出现在人物正下方，并弹软键盘。 */
    fun show(anchor: Rect) {
        if (isVisible) return
        val dm = ctx.resources.displayMetrics
        // 默认位置：人物正下方（贴近人物，便于连续交流），钳制在屏内
        params.x = anchor.left.coerceIn(0, (dm.widthPixels - params.width).coerceAtLeast(0))
        params.y = (anchor.bottom + dp(GAP_DP)).coerceIn(0, (dm.heightPixels - params.height).coerceAtLeast(0))
        try { windowManager.addView(rootView, params) } catch (e: Exception) {
            Log.w(TAG, "Failed to show input window: ${e.message}")
            return
        }
        // 延迟请求焦点并弹键盘；部分机型一次不弹，稍后再强弹一次兜底
        mainHandler.postDelayed({ requestIme(InputMethodManager.SHOW_IMPLICIT) }, FOCUS_DELAY_MS)
        mainHandler.postDelayed({ requestIme(InputMethodManager.SHOW_FORCED) }, FOCUS_DELAY_MS * 2)
    }

    /** 隐藏输入框（收起键盘并移除窗口）。 */
    fun hide() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            imm.hideSoftInputFromWindow(editText.windowToken, 0)
        } catch (_: Exception) {}
        if (isVisible) {
            try { windowManager.removeView(rootView) } catch (_: Exception) {}
        }
    }

    /** 清空已输入的文字。 */
    fun clearText() {
        editText.setText("")
        refreshSendButton()
    }

    /**
     * 切换发送中状态：禁用编辑并显示加载指示；结束后恢复并重新弹键盘，
     * 便于连续发送。输入框在发送后保持打开（产品决定）。
     */
    fun setSending(sending: Boolean) {
        isSending = sending
        editText.isEnabled = !sending
        sendButton.isEnabled = !sending
        sendButton.visibility = if (sending) View.GONE else View.VISIBLE
        loadingView.visibility = if (sending) View.VISIBLE else View.GONE
        if (sending) {
            try {
                imm.hideSoftInputFromWindow(editText.windowToken, 0)
            } catch (_: Exception) {}
        } else if (isVisible) {
            editText.requestFocus()
            try {
                imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Exception) {}
        }
    }

    // ================ 内部 ================

    /** 左侧抓手拖动：在屏幕内移动输入条。 */
    private fun handleDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartX = params.x
                dragStartY = params.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dm = ctx.resources.displayMetrics
                params.x = (dragStartX + (event.rawX - dragStartRawX).toInt())
                    .coerceIn(0, (dm.widthPixels - params.width).coerceAtLeast(0))
                params.y = (dragStartY + (event.rawY - dragStartRawY).toInt())
                    .coerceIn(0, (dm.heightPixels - params.height).coerceAtLeast(0))
                try { windowManager.updateViewLayout(rootView, params) } catch (_: Exception) {}
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
            else -> return false
        }
    }

    private fun requestIme(flags: Int) {
        if (!isVisible) return
        editText.requestFocus()
        try {
            imm.showSoftInput(editText, flags)
        } catch (_: Exception) {}
    }

    private fun sendIfPossible() {
        if (isSending) return
        val text = editText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        onSend(text)
    }

    /**
     * 依据输入框是否为空刷新发送钮的可用态与配色，与主页 ChatInputBar 一致：
     * 可用 = 实色 primary 圆 + onPrimary 图标；禁用 = 40% 透明。
     */
    private fun refreshSendButton() {
        val hasText = !editText.text.isNullOrBlank()
        sendButton.isEnabled = hasText
        val alpha = if (hasText) 1f else 0.4f
        sendButton.background = oval(withAlpha(palette.primary, alpha))
        // 用 imageTintList（SRC_IN 准确上色）而非 setColorFilter（SRC_ATOP 相乘发灰）
        sendButton.imageTintList = ColorStateList.valueOf(withAlpha(palette.onPrimary, alpha))
    }

    /** 颜色整体缩放 alpha（0~1），用于禁用态配色。 */
    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (android.graphics.Color.alpha(color) * alpha).roundToInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            setShape(GradientDrawable.OVAL)
            cornerRadius = dp(SEND_BUTTON_DP).toFloat() / 2f
        }

    private fun dp(v: Number): Int = (v.toFloat() * density).roundToInt()

    private fun Int.withAlpha(a: Int): Int = this and 0x00FFFFFF or (a shl 24)
}
