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
 * 线程模型与现有 `Live2dDelegate.bgR/G/B/A` 一致：**主线程写 [desiredPath]**（volatile），
 * **所有方法仅在 GL 线程调用**（由 `run()` / `onSurfaceCreated()` 驱动）。
 *
 * GL 纹理生命周期：
 * - 路径变化（换壁纸）→ 删除旧纹理 → 解码 cover 到屏幕尺寸 → `GLUtils.texImage2D` 上传；
 * - GL 上下文重建（`onSurfaceCreated`）→ [reset] 删纹理/关 program，下帧自动重载。
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
    private var textureId = 0
    private var shader: Live2dSpriteShader? = null
    private var sprite: Live2dSprite? = null

    /** GL 上下文重建时调用：旧上下文里的纹理 / program 全部失效。 */
    fun reset() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        loadedPath = null
        sprite = null
        shader?.close()
        shader = null
    }

    /** 每帧调用（清屏之后、模型绘制之前）。 */
    fun onFrame(w: Int, h: Int) {
        updateIfNeeded(w, h)
        draw(w, h)
    }

    private fun updateIfNeeded(w: Int, h: Int) {
        if (desiredPath == loadedPath) return
        // 无论成败都先标记已尝试，防止解码失败后每帧重试
        loadedPath = desiredPath
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        val path = desiredPath ?: return
        if (w <= 0 || h <= 0) return
        val bmp = decodeCover(path, w, h) ?: return
        textureId = upload(bmp)
        if (textureId != 0) ensureSprite(w, h)
        bmp.recycle()
    }

    private fun draw(w: Int, h: Int) {
        val sp = sprite ?: return
        if (textureId == 0) return
        // 保底全屏：窗口尺寸变化时 sprite 矩形跟着窗口走，防止漂移
        sp.resize(w * 0.5f, h * 0.5f, w.toFloat(), h.toFloat())
        sp.setColor(1f, 1f, 1f, 1f)
        sp.setBlur(blur)
        sp.setWindowSize(w, h)
        sp.renderImmediate(textureId, UV_FULL)
    }

    private fun ensureSprite(w: Int, h: Int) {
        if (sprite != null) return
        val sh = shader ?: Live2dSpriteShader().also { shader = it }
        sprite = Live2dSprite(w * 0.5f, h * 0.5f, w.toFloat(), h.toFloat(), 0, sh.programId)
    }

    private fun upload(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
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
    }
}
