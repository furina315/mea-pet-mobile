package com.meapet.mobile.live2d

import android.opengl.GLES20

/**
 * 场景毛玻璃合成器（GL 线程内使用）。
 *
 * 供 [Live2dDelegate.run] 在「设置页打开」等需要毛玻璃背景的场景使用：
 * - [beginScene] 把渲染目标切到全分辨率离屏 FBO，调用方随后照常绘制
 *   清屏色 / 壁纸 / Live2D 模型（所有绘制都会落进该 FBO）；
 * - [compositeToScreen] 把 FBO 内容经「下采样 + 两遍可分离高斯」模糊后铺回屏幕。
 *
 * 模糊复用 [WallpaperBlurShader]（33-tap 可分离高斯），流程与 [WallpaperRenderer]
 * 的模糊壁纸完全同构（半分辨率坐标系）；模糊强度固定取壁纸模糊的最大档
 * （σ = [Live2dDefine.MAX_BLUR_SIGMA_PX]，半分辨率下减半），保证毛玻璃观感足够强。
 *
 * 线程模型：所有方法仅在 GL 线程调用（由 `run()` 驱动）；尺寸变化时懒重建缓冲，
 * GL 上下文重建（`onSurfaceCreated`）时必须调用 [reset] 释放旧资源。
 */
class SceneBlurCompositor {

    private companion object {
        /** 全屏四边形 UV（FBO 纹理方向：顶行 v=1）。与 [WallpaperRenderer] 内部约定一致。 */
        val UV_FBO = floatArrayOf(1f, 1f, 0f, 1f, 0f, 0f, 1f, 0f)
    }

    // ── 场景 FBO（全分辨率，颜色纹理 + 可写缓冲）──
    private var sceneFbo = 0
    private var sceneTex = 0
    private var sceneTexW = 0
    private var sceneTexH = 0

    // ── 模糊目标：半分辨率 FBO ×2（blurTex = 下采样目标，blurBuf = 中间缓冲）──
    private var blurFbo = 0
    private var blurTex = 0
    private var blurTexW = 0
    private var blurTexH = 0
    private var blurBufFbo = 0
    private var blurBuf = 0
    private var blurBufW = 0
    private var blurBufH = 0

    private var blurShader: WallpaperBlurShader? = null

    /** GL 上下文重建时调用：旧上下文里的纹理 / FBO / program 全部失效。 */
    fun reset() {
        deleteSceneTarget()
        deleteBlurTarget()
        blurShader?.close()
        blurShader = null
    }

    /**
     * 绑定场景 FBO 并设置 viewport。
     *
     * @return true = 场景应绘制进离屏 FBO（调用方之后必须调用 [compositeToScreen] 上屏）；
     *         false = 资源不可用，调用方应直接按原路径绘制到屏幕（降级，不影响功能）。
     */
    fun beginScene(w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        if (!ensureTargets(w, h)) {
            // 确保降级路径从干净的默认帧缓冲开始
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return false
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFbo)
        GLES20.glViewport(0, 0, w, h)
        return true
    }

    /**
     * 把场景 FBO 的内容模糊并铺回默认帧缓冲（屏幕）。
     * 仅可在 [beginScene] 返回 true 后调用。
     */
    fun compositeToScreen(w: Int, h: Int) {
        val bs = blurShader
        if (bs == null || bs.programId == 0 || sceneTex == 0) {
            // 资源异常：恢复默认帧缓冲并放弃本帧输出（下帧会尝试重建）
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return
        }

        // 半分辨率坐标下的 σ 与截断半径（与壁纸模糊同一套换算）
        val sigma = Live2dDefine.blurToSigma(1f) / Live2dDefine.BLUR_DOWNSAMPLE
        val radius = (sigma * 3f).coerceIn(1f, 16f)

        if (blurFbo == 0 || blurTex == 0 || blurBuf == 0 || blurBufFbo == 0) {
            // 模糊缓冲不可用：把场景原样铺回屏幕（兜底，避免黑屏）
            blitSceneToScreen(w, h, bs)
            return
        }

        // 三个 pass 都铺满各自目标且场景不透明，关掉混合防脏数据混入（FBO 未清屏）
        val blendEnabled = GLES20.glIsEnabled(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_BLEND)

        // ── pass 0：场景 FBO → 半分辨率 FBO（线性下采样，sigma=0）──
        GLES20.glViewport(0, 0, blurTexW, blurTexH)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFbo)
        bs.render(
            textureId = sceneTex,
            dirX = 0f, dirY = 0f,
            srcW = sceneTexW, srcH = sceneTexH,
            sigma = 0f,
            radius = 1f,
            uv = UV_FBO
        )

        // ── pass 1：水平高斯（blurTex → blurBuf）──
        GLES20.glViewport(0, 0, blurBufW, blurBufH)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurBufFbo)
        bs.render(
            textureId = blurTex,
            dirX = 1f, dirY = 0f,
            srcW = blurTexW, srcH = blurTexH,
            sigma = sigma,
            radius = radius,
            uv = UV_FBO
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
            uv = UV_FBO
        )

        if (blendEnabled) GLES20.glEnable(GLES20.GL_BLEND)
    }

    /** 兜底：把场景纹理不经模糊直接铺满屏幕（资源异常时避免黑屏）。 */
    private fun blitSceneToScreen(w: Int, h: Int, bs: WallpaperBlurShader) {
        val blendEnabled = GLES20.glIsEnabled(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        bs.render(
            textureId = sceneTex,
            dirX = 0f, dirY = 0f,
            srcW = sceneTexW, srcH = sceneTexH,
            sigma = 0f,
            radius = 1f,
            uv = UV_FBO
        )
        if (blendEnabled) GLES20.glEnable(GLES20.GL_BLEND)
    }

    // ── 资源创建 / 释放（GL 线程内）─────────────────────

    private fun ensureTargets(w: Int, h: Int): Boolean {
        if (blurShader == null) {
            val s = WallpaperBlurShader()
            if (s.programId == 0) return false
            blurShader = s
        }

        if (sceneFbo == 0 || sceneTexW != w || sceneTexH != h) {
            deleteSceneTarget()
            if (!createSceneFbo(w, h)) {
                deleteSceneTarget()
                return false
            }
        }

        ensureBlurTarget(w, h)
        if (blurFbo == 0 || blurTex == 0 || blurBuf == 0 || blurBufFbo == 0) {
            return false
        }
        return true
    }

    private fun createSceneFbo(w: Int, h: Int): Boolean {
        sceneTex = createRgbaTexture(w, h)
        if (sceneTex == 0) return false

        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        sceneFbo = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, sceneTex, 0
        )
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            return false
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        sceneTexW = w
        sceneTexH = h
        return true
    }

    /**
     * 创建/重建模糊用半分辨率 FBO（blurTex = 下采样目标，blurBuf = 中间缓冲）。
     * 尺寸变化或首次加载时重建；结构对照 [WallpaperRenderer.ensureBlurTarget]。
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
            deleteBlurTarget()
            return
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        blurBufW = halfW
        blurBufH = halfH
        blurTexW = halfW
        blurTexH = halfH
    }

    private fun deleteSceneTarget() {
        if (sceneTex != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(sceneTex), 0)
            sceneTex = 0
        }
        if (sceneFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(sceneFbo), 0)
            sceneFbo = 0
        }
        sceneTexW = 0
        sceneTexH = 0
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
        blurTexW = 0
        blurTexH = 0
        blurBufW = 0
        blurBufH = 0
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
}
