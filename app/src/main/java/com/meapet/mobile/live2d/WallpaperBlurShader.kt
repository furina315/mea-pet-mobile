package com.meapet.mobile.live2d

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 可分离高斯模糊着色器（单轴 33-tap）。
 *
 * 供 [WallpaperRenderer] 的三遍模糊使用（均在半分辨率坐标系）：
 * - pass 0：无模糊的线性下采样（sigma=0、radius=1 → 仅中心 tap，等价于点采样铺满）；
 * - pass 1：水平高斯 → 中间缓冲；
 * - pass 2：垂直高斯 → 画回默认帧缓冲（线性放大回全屏）。
 *
 * 权重在片元内实时计算并归一化，避免手工预计算表与 σ 不匹配；
 * `uDir` 决定沿水平/垂直采样，`uSize` 为输入纹理尺寸，用于把像素偏移归一化到 UV。
 */
class WallpaperBlurShader : AutoCloseable {

    companion object {
        private const val TAG = "WallpaperBlurShader"

        private val VERTEX = """
            #version 100
            attribute vec3 position;
            attribute vec2 uv;
            varying vec2 vuv;
            void main(void) {
                gl_Position = vec4(position, 1.0);
                vuv = uv;
            }
        """.trimIndent()

        /**
         * 33-tap 高斯（±16 像素，约 2.2σ 截断）。循环边界为编译期常量，满足 ES 2.0 循环限制；
         * 实际有效半径由 `uRadius` 动态截断——σ 小（低模糊）时外部 tap 权重≈0，跳过采样，
         * 保持低模糊档位计算量小、又不会出现 box 感。
         */
        private val FRAGMENT = """
            #version 100
            precision mediump float;
            varying vec2 vuv;
            uniform sampler2D texture;
            uniform vec2 uDir;
            uniform vec2 uSize;
            uniform float uSigma;
            uniform float uRadius;
            void main(void) {
                float sigma = max(uSigma, 0.5);
                vec2 texel = vec2(1.0) / uSize;
                float twoSigma2 = 2.0 * sigma * sigma;
                float wsum = 0.0;
                vec4 sum = vec4(0.0);
                for (int i = 0; i < 33; i++) {
                    float off = float(i - 16);
                    if (abs(off) <= uRadius) {
                        float w = exp(-(off * off) / twoSigma2);
                        sum += texture2D(texture, vuv + texel * uDir * off) * w;
                        wsum += w;
                    }
                }
                gl_FragColor = sum / wsum;
            }
        """.trimIndent()

        /** 全屏四边形顶点（NDC，顶点顺序：右上→左上→左下→右下）。 */
        private val NDC_VERTEX = floatArrayOf(
            1f, 1f,
            -1f, 1f,
            -1f, -1f,
            1f, -1f
        )
    }

    val programId: Int = createShader()

    private val positionLocation = GLES20.glGetAttribLocation(programId, "position")
    private val uvLocation = GLES20.glGetAttribLocation(programId, "uv")
    private val textureLocation = GLES20.glGetUniformLocation(programId, "texture")
    private val dirLocation = GLES20.glGetUniformLocation(programId, "uDir")
    private val sizeLocation = GLES20.glGetUniformLocation(programId, "uSize")
    private val sigmaLocation = GLES20.glGetUniformLocation(programId, "uSigma")
    private val radiusLocation = GLES20.glGetUniformLocation(programId, "uRadius")

    private val posBuf: FloatBuffer = newFloatBuffer(8)
    private val uvBuf: FloatBuffer = newFloatBuffer(8)

    /** 恢复用：记录调用前的 program，pass 结束后 bind 回去，避免污染后续渲染（模型 blit 不 bind program）。 */
    private val prevProgram = IntArray(1)

    /**
     * 以全屏四边形渲染一次高斯 pass（当前 viewport 必须已按目标尺寸设置）。
     *
     * @param textureId 输入纹理（第 1 遍=壁纸原图，第 2 遍=半分辨率 FBO 纹理）。
     * @param dirX/dirY 采样方向（水平 (1,0)、垂直 (0,1)）。
     * @param srcW/srcH 输入纹理尺寸（像素，用于归一化偏移）。
     * @param sigma     高斯 σ（输入纹理坐标下的像素值）。
     * @param radius    截断半径（像素，≤16）：该半径外的采样权重≈0，跳过以省采样。
     * @param uv        UV 映射：源 bitmap 纹理用 [WallpaperRenderer.UV_FULL]
     *                  （顶行 v=0），FBO 纹理用 [WallpaperRenderer.UV_FBO]（顶行 v=1），
     *                  两遍共用保证最终画面不上下颠倒。
     */
    fun render(
        textureId: Int,
        dirX: Float,
        dirY: Float,
        srcW: Int,
        srcH: Int,
        sigma: Float,
        radius: Float,
        uv: FloatArray
    ) {
        if (programId == 0) return
        // 记录当前 program，pass 结束后恢复——后续渲染（模型 FBO blit）依赖自己当前绑定的 program
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, prevProgram, 0)

        GLES20.glUseProgram(programId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureLocation, 0)
        GLES20.glUniform2f(dirLocation, dirX, dirY)
        GLES20.glUniform2f(sizeLocation, srcW.toFloat(), srcH.toFloat())
        GLES20.glUniform1f(sigmaLocation, sigma)
        GLES20.glUniform1f(radiusLocation, radius.coerceIn(1f, 16f))

        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glEnableVertexAttribArray(uvLocation)
        posBuf.clear(); posBuf.put(NDC_VERTEX); posBuf.position(0)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        uvBuf.clear(); uvBuf.put(uv); uvBuf.position(0)
        GLES20.glVertexAttribPointer(uvLocation, 2, GLES20.GL_FLOAT, false, 0, uvBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)

        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(uvLocation)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        // 恢复上一个 program，避免污染后续渲染（模型 blit 不 bind program 就采样）
        if (prevProgram[0] != 0) GLES20.glUseProgram(prevProgram[0])
    }

    override fun close() {
        GLES20.glDeleteProgram(programId)
    }

    private fun newFloatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun createShader(): Int {
        val vertexShaderId = compileShader(VERTEX, GLES20.GL_VERTEX_SHADER)
        if (!checkShaderCompiled(vertexShaderId, "vertex")) return 0

        val fragmentShaderId = compileShader(FRAGMENT, GLES20.GL_FRAGMENT_SHADER)
        if (!checkShaderCompiled(fragmentShaderId, "fragment")) return 0

        val programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShaderId)
        GLES20.glAttachShader(programId, fragmentShaderId)
        GLES20.glLinkProgram(programId)

        if (!checkProgramLinked(programId)) return 0

        GLES20.glUseProgram(programId)
        GLES20.glDeleteShader(vertexShaderId)
        GLES20.glDeleteShader(fragmentShaderId)

        return programId
    }

    private fun compileShader(source: String, shaderType: Int): Int {
        val shaderId = GLES20.glCreateShader(shaderType)
        GLES20.glShaderSource(shaderId, source)
        GLES20.glCompileShader(shaderId)
        return shaderId
    }

    private fun checkShaderCompiled(shaderId: Int, name: String): Boolean {
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shaderId, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shaderId)
            Log.e(TAG, "Shader compile error [$name]: $info")
            GLES20.glDeleteShader(shaderId)
            return false
        }
        return true
    }

    private fun checkProgramLinked(programId: Int): Boolean {
        val linked = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val info = GLES20.glGetProgramInfoLog(programId)
            Log.e(TAG, "Program link error: $info")
            GLES20.glDeleteProgram(programId)
            return false
        }
        return true
    }
}
