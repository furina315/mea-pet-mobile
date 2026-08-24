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
            uniform float uBlur;
            uniform vec2 uTexSize;
            void main(void) {
                vec4 color = texture2D(texture, vuv);
                if (uBlur > 0.001) {
                    // 9-tap 高斯模糊（近似 3x3）。偏移随模糊强度线性放大，
                    // 归一化到纹理尺寸避免采样溢出。blur=0 时分支不执行，退化为原采样。
                    vec2 texel = vec2(1.0) / uTexSize;
                    float radius = 1.0 + uBlur * 5.0;
                    vec2 off = texel * radius;
                    vec4 sum = color;
                    sum += texture2D(texture, vuv + vec2(-off.x, -off.y));
                    sum += texture2D(texture, vuv + vec2( 0.0,   -off.y));
                    sum += texture2D(texture, vuv + vec2( off.x, -off.y));
                    sum += texture2D(texture, vuv + vec2(-off.x,  0.0));
                    sum += texture2D(texture, vuv + vec2( off.x,  0.0));
                    sum += texture2D(texture, vuv + vec2(-off.x,  off.y));
                    sum += texture2D(texture, vuv + vec2( 0.0,    off.y));
                    sum += texture2D(texture, vuv + vec2( off.x,  off.y));
                    color = sum / 9.0;
                }
                gl_FragColor = color * baseColor;
            }
        """.trimIndent()
    }
}
