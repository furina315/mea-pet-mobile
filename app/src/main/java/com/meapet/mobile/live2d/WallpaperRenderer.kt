package com.meapet.mobile.live2d

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils

/**
 * 主界面背景壁纸渲染器（GL 线程内绘制）。
 *
 * 在 [Live2dDelegate.run] 中「纯色清屏之后、Live2D 模型绘制之前」调用 [onFrame]，
 * 用一张全屏纹理把壁纸铺满窗口（cover 中心裁剪，不拉伸变形）。无壁纸时是纯 no-op，
 * 清屏的纯色兜底照旧，向后完全兼容；悬浮窗走独立渲染器，天然不受影响。
 *
 * 线程模型与现有 `Live2dDelegate.bgR/G/B/A` 一致：**主线程写 [desiredPath] / [blur]**
 * （volatile），**所有方法仅在 GL 线程调用**（由 `run()` / `onSurfaceCreated()` 驱动）。
 *
 * 模糊采用 **下采样 + 两遍可分离高斯**（[WallpaperBlurShader]）：
 * - pass 0：原图线性采样到半分辨率 FBO（下采样本身即低通）；
 * - pass 1：半分辨率下水平高斯；
 * - pass 2：半分辨率下垂直高斯，同时铺回全屏（线性放大）。
 * 高斯在**半分辨率坐标系**里计算，σ（0~6px）与截断半径（≤16）都落在 33-tap 内核
 * 可表示的范围，高档位不饱和。σ 随 blur 的**平方根曲线**（见 [Live2dDefine.blurToSigma]）
 * 映射，低档位有可感知变化、尾部继续平滑变强。blur=0 时直画原图，零额外开销。
 *
 * GL 资源生命周期：
 * - 路径变化（换壁纸）→ 删旧纹理/FBO/着色器 → 解码 cover 到屏幕尺寸 → 上传；
 * - GL 上下文重建（`onSurfaceCreated`）→ [reset] 删全部资源，下帧自动重载。
 */
class WallpaperRenderer {

    /** 主线程写入；null = 默认纯色背景。 */
    @Volatile
    var desiredPath: String? = null

    /** 主线程写入；0~1 背景模糊强度（0 = 不模糊）。 */
    @Volatile
    var blur: Float = 0f

    /** GL 线程：已加载（或尝试过）的路径，避免失败每帧重试。 */
    private var loadedPath: String? = null

    // ── 模糊目标：半分辨率 FBO（含颜色纹理 + 可写缓冲）──
    private var blurFbo = 0          // 可写 FBO 对象（绑 blurTex）
    private var blurTex = 0          // 半分辨率颜色纹理
    private var blurTexW = 0
    private var blurTexH = 0
    private var blurBufFbo = 0       // 中间缓冲 FBO（绑 blurBuf）
    private var blurBuf = 0          // 中间颜色纹理（pass1 目标 = pass2 采样源）
    private var blurBufW = 0
    private var blurBufH = 0

    // ── 壁纸主纹理 / 上屏精灵 ──
    private var textureId = 0
    private var shader: Live2dSpriteShader? = null
    private var sprite: Live2dSprite? = null

    // ── 两遍高斯着色器 ──
    private var blurShader: WallpaperBlurShader? = null

    /** GL 上下文重建时调用：旧上下文里的纹理 / FBO / program 全部失效。 */
    fun reset() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        deleteBlurTarget()
        loadedPath = null
        sprite = null
        shader?.close()
        shader = null
        blurShader?.close()
        blurShader = null
    }

    /** 每帧调用（清屏之后、模型绘制之前）。 */
    fun onFrame(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        updateIfNeeded(w, h)
        if (textureId != 0) {
            if (blur > 0.001f) drawBlurred(w, h) else drawPlain(w, h)
        }
    }

    private fun updateIfNeeded(w: Int, h: Int) {
        if (desiredPath == loadedPath) return
        // 无论成败都先标记已尝试，防止解码失败后每帧重试
        loadedPath = desiredPath
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        deleteBlurTarget()
        val path = desiredPath ?: return
        val bmp = decodeCover(path, w, h) ?: return
        textureId = upload(bmp)
        if (textureId != 0) {
            ensureSprite(w, h)
            ensureBlurTarget(w, h)
        }
        bmp.recycle()
    }

    /** blur=0：原图直画，退化为与上一版本完全相同的路径。 */
    private fun drawPlain(w: Int, h: Int) {
        val sp = sprite ?: return
        // 保底全屏：窗口尺寸变化时 sprite 矩形跟着窗口走，防止漂移
        sp.resize(w * 0.5f, h * 0.5f, w.toFloat(), h.toFloat())
        sp.setColor(1f, 1f, 1f, 1f)
        sp.setWindowSize(w, h)
        sp.renderImmediate(textureId, UV_FULL)
    }

    /**
     * blur>0：下采样 + 两遍可分离高斯，都在半分辨率坐标系里进行。
     */
    private fun drawBlurred(w: Int, h: Int) {
        val bs = blurShader ?: return
        if (blurFbo == 0 || blurTex == 0 || blurBuf == 0 || bs.programId == 0) {
            drawPlain(w, h)
            return
        }

        // 半分辨率坐标下的 σ 与截断半径（3σ clamp 到 16，贴合 33-tap 循环上限）
        val sigma = Live2dDefine.blurToSigma(blur) / Live2dDefine.BLUR_DOWNSAMPLE
        val radius = (sigma * 3f).coerceIn(1f, 16f)

        // 三个 pass 都铺满各自目标且壁纸不透明，关掉混合防脏数据混入（FBO 未清屏）
        val blendEnabled = GLES20.glIsEnabled(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_BLEND)

        // ── pass 0：原图下采样到半分辨率 FBO（线性采样）──
        GLES20.glViewport(0, 0, blurTexW, blurTexH)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFbo)
        bs.render(
            textureId = textureId,
            dirX = 0f, dirY = 0f,           // 无模糊：线性下采样
            srcW = w, srcH = h,
            sigma = 0f,
            radius = 1f,
            uv = UV_FULL                     // 源 bitmap 纹理：顶行 v=0
        )

        // ── pass 1：水平高斯（blurTex → blurBuf，缓冲 FBO）──
        GLES20.glViewport(0, 0, blurBufW, blurBufH)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurBufFbo)
        bs.render(
            textureId = blurTex,
            dirX = 1f, dirY = 0f,
            srcW = blurTexW, srcH = blurTexH,
            sigma = sigma,
            radius = radius,
            uv = UV_FBO                      // FBO 纹理：顶行 v=1
        )

        // ── pass 2：垂直高斯 → 默认帧缓冲，线性放大回全屏 ──
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        bs.render(
            textureId = blurBuf,
            dirX = 0f, dirY = 1f,
            srcW = blurBufW, srcH = blurBufH,
            sigma = sigma,
            radius = radius,
            uv = UV_FBO                      // FBO 纹理：顶行 v=1
        )

        if (blendEnabled) GLES20.glEnable(GLES20.GL_BLEND)
    }

    private fun ensureSprite(w: Int, h: Int) {
        if (sprite != null) return
        val sh = shader ?: Live2dSpriteShader().also { shader = it }
        sprite = Live2dSprite(w * 0.5f, h * 0.5f, w.toFloat(), h.toFloat(), 0, sh.programId)
        blurShader ?: WallpaperBlurShader().also { blurShader = it }
    }

    /**
     * 创建/重建模糊用半分辨率 FBO（blurTex = 可采样源/最终颜色，blurBuf = 中间可写缓冲）。
     * 尺寸变化或首次加载时重建。
     */
    private fun ensureBlurTarget(w: Int, h: Int) {
        val ds = Live2dDefine.BLUR_DOWNSAMPLE
        val halfW = maxOf(1, (w + ds - 1) / ds)
        val halfH = maxOf(1, (h + ds - 1) / ds)
        if (blurFbo != 0 && blurTexW == halfW && blurTexH == halfH) return
        deleteBlurTarget()

        blurTex = createRgbaTexture(halfW, halfH)
        if (blurTex == 0) return

        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        blurFbo = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, blurTex, 0
        )
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            deleteBlurTarget()
            return
        }

        blurBuf = createRgbaTexture(halfW, halfH)
        if (blurBuf == 0) {
            deleteBlurTarget()
            return
        }
        val bufFboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, bufFboIds, 0)
        blurBufFbo = bufFboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurBufFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, blurBuf, 0
        )
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(blurBufFbo), 0)
            blurBufFbo = 0
            deleteBlurTarget()
            return
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        blurBufW = halfW
        blurBufH = halfH
        blurTexW = halfW
        blurTexH = halfH
    }

    private fun createRgbaTexture(width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return id
    }

    private fun deleteBlurTarget() {
        if (blurTex != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(blurTex), 0)
            blurTex = 0
        }
        if (blurFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(blurFbo), 0)
            blurFbo = 0
        }
        if (blurBuf != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(blurBuf), 0)
            blurBuf = 0
        }
        if (blurBufFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(blurBufFbo), 0)
            blurBufFbo = 0
        }
        blurTexW = 0; blurTexH = 0
        blurBufW = 0; blurBufH = 0
    }

    private fun upload(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0] // 全屏单层采样，无需 mipmap / wrap
    }

    /**
     * 解码并按 cover（中心裁剪）缩放到屏幕尺寸。
     * 两段采样：先按「至少覆盖屏幕」粗采样，再精确缩放，控制单帧解码量防 OOM。
     */
    private fun decodeCover(path: String, tw: Int, th: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= tw && bounds.outHeight / (sample * 2) >= th) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null

        // cover 中心裁剪：按屏幕宽高比在源图上取居中窗口，再精确缩放到屏幕尺寸
        val targetRatio = tw.toFloat() / th
        val srcRatio = decoded.width.toFloat() / decoded.height
        val cropW: Int
        val cropH: Int
        if (srcRatio > targetRatio) {
            cropH = decoded.height
            cropW = (decoded.height * targetRatio).toInt().coerceAtMost(decoded.width)
        } else {
            cropW = decoded.width
            cropH = (decoded.width / targetRatio).toInt().coerceAtMost(decoded.height)
        }
        val sx = (decoded.width - cropW) / 2
        val sy = (decoded.height - cropH) / 2
        val cropped = Bitmap.createBitmap(decoded, sx, sy, cropW, cropH)
        if (cropped !== decoded) decoded.recycle()

        val scaled = if (cropped.width == tw && cropped.height == th) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, tw, th, true).also {
                if (it !== cropped) cropped.recycle()
            }
        }
        return scaled
    }

    private companion object {
        /**
         * 全屏四边形 UV（Bitmap 纹理方向）。
         *
         * `GLUtils.texImage2D` 上传的 Bitmap：顶部一行对应 v=0、底部对应 v=1
         * （与 FBO 纹理相反）。Live2dView 离屏 blit 用的是 v 从 1 到 0 的映射
         * （给 FBO 纹理用），直接复用会导致壁纸上下颠倒，故这里用 v 从 0 到 1。
         * 顶点顺序：右上(1,1) → 左上(0,1) → 左下(0,0) → 右下(1,0)。
         */
        val UV_FULL = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 1f, 1f)

        /**
         * 全屏四边形 UV（FBO 纹理方向）。
         *
         * 渲染到 FBO 时：视口顶部 = v=1、底部 = v=0（与 Bitmap 纹理相反）。
         * 所有 FBO 纹理（blurTex/blurBuf）都用该映射，与源图的 [UV_FULL] 抵消，
         * 最终画面方向与直画原图一致。
         */
        val UV_FBO = floatArrayOf(1f, 1f, 0f, 1f, 0f, 0f, 1f, 0f)
    }
}
