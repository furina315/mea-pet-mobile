package com.meapet.mobile.live2d

import com.live2d.sdk.cubism.framework.CubismFrameworkConfig.LogLevel

object Live2dDefine {
    object Scale {
        const val DEFAULT = 1.0f
        const val MAX = 2.0f
        const val MIN = 0.8f
    }

    object LogicalView {
        const val LEFT = -1.0f
        const val RIGHT = 1.0f
        const val BOTTOM = -1.0f
        const val TOP = 1.0f
    }

    object MaxLogicalView {
        const val LEFT = -2.0f
        const val RIGHT = 2.0f
        const val BOTTOM = -2.0f
        const val TOP = 2.0f
    }

    object MotionGroup {
        const val IDLE = "Idle"
        const val TAP_BODY = "TapBody"
    }

    object Priority {
        const val NONE = 0
        const val IDLE = 1
        const val NORMAL = 2
        const val FORCE = 3
    }

    const val MOC_CONSISTENCY_VALIDATION_ENABLE = true
    const val DEBUG_LOG_ENABLE = true
    const val PREMULTIPLIED_ALPHA_ENABLE = true
    val CUBISM_LOGGING_LEVEL = LogLevel.VERBOSE

    /** 壁纸背景模糊：blur∈[0,1] → σ ∈ [0, MAX_BLUR_SIGMA_PX]。 */
    const val MAX_BLUR_SIGMA_PX = 12f

    /**
     * 壁纸背景模糊强度→σ 映射（平方根曲线，感知近似线性）。
     *
     * 人的视觉对模糊强度的感知近似对数（对 σ 的小变化不敏感），线性映射会让低档位
     * 几乎看不出差别——实测前 15% 滑杆无感（5% → 0.6px、15% → 1.8px）。开方曲线把
     * 低档位放大、高档位压缩：5% → 2.7px、15% → 4.7px、50% → 8.5px、100% → 12px，
     * 两端都连续生效。
     *
     * 上限 12px 由 33-tap 高斯内核（±16px）决定：σ=12 时截断半径 3σ=18 略超 16，
     * 但 16px 处权重已衰减到 e^-(16²/2·12²)≈0.4%（覆盖 99.2%），视觉无差。
     * 半分辨率 FBO 里 σ 再减半（≤6），全程可表示、尾部不饱和。
     */
    fun blurToSigma(blur: Float): Float =
        MAX_BLUR_SIGMA_PX * kotlin.math.sqrt(blur.coerceIn(0f, 1f))

    /** 模糊降采样系数：先把壁纸下采样到 1/2 再做高斯（半分辨率坐标系里 σ/半径都可表示）。 */
    const val BLUR_DOWNSAMPLE = 2

    /** Shader source code embedded to avoid external file dependencies */
    object ShaderSource {
        val VERTEX = """
            #version 100
            attribute vec3 position;
            attribute vec2 uv;
            varying vec2 vuv;
            void main(void) {
                gl_Position = vec4(position, 1.0);
                vuv = uv;
            }
        """.trimIndent()

        val FRAGMENT = """
            #version 100
            precision mediump float;
            varying vec2 vuv;
            uniform sampler2D texture;
            uniform vec4 baseColor;
            void main(void) {
                gl_FragColor = texture2D(texture, vuv) * baseColor;
            }
        """.trimIndent()
    }
}
