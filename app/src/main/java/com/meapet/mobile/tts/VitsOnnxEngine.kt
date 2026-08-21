package com.meapet.mobile.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.meapet.mobile.tts.model.TtsModelManager
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * VITS 四模块 ONNX 引擎封装。
 *
 * 对应 `mea_vits_inference.py` 的 4 个 `ort.InferenceSession`：
 * `enc_p`(fp32) / `dp`(fp32) / `flow`(fp16) / `dec`(fp16)。
 *
 * - 权重 fp16 的 flow/dec 图 I/O 仍声明 fp32，ORT 自动 cast——直接喂 fp32 即可。
 * - 单说话人，`sid` 恒为 0。
 * - 所有 run 均返回「按 [channel][time] 展开的二维数组」，便于上层胶水运算。
 *
 * session 懒加载（首次合成时才读 73MB 权重），用后需 [close] 释放 native 内存。
 */
class VitsOnnxEngine(private val modelManager: TtsModelManager) {

    companion object {
        private const val TAG = "VitsOnnxEngine"
        const val SPEAKER_ID = 0L
        const val HIDDEN_CHANNELS = 192
    }

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private val sessions = mutableMapOf<String, OrtSession>()
    private val sessionLock = Any()

    private fun session(name: String): OrtSession = synchronized(sessionLock) {
        sessions.getOrPut(name) {
            val file: File = modelManager.modelFile("$name.onnx")
            Log.i(TAG, "加载 ONNX 模块 $name (${file.length() / 1024 / 1024}MB)")
            env.createSession(file.absolutePath, OrtSession.SessionOptions())
        }
    }

    /** enc_p：x / x_lengths → x_enc, m_p, logs_p, x_mask（均 [1,192|T,t]）。 */
    fun runEnc(x: LongArray): EncResult {
        val tX = x.size
        session("enc_p").run(
            mapOf(
                "x" to longTensor(x, longArrayOf(1, tX.toLong())),
                "x_lengths" to longTensor(longArrayOf(tX.toLong()), longArrayOf(1))
            )
        ).use { out ->
            return EncResult(
                xEnc = to2D(tensor(out, "x_enc")),
                mP = to2D(tensor(out, "m_p")),
                logsP = to2D(tensor(out, "logs_p")),
                xMask = to2D(tensor(out, "x_mask"))[0]   // [1,1,t_x] → [t_x]
            )
        }
    }

    /** dp：x_enc / x_mask / sid / noise_scale_w → logw [t_x]。 */
    fun runDp(xEnc: Array<FloatArray>, xMask: FloatArray, noiseScaleW: Float): FloatArray {
        val tX = xEnc[0].size
        session("dp").run(
            mapOf(
                "x_enc" to floatTensor(xEnc, longArrayOf(1, HIDDEN_CHANNELS.toLong(), tX.toLong())),
                "x_mask" to floatTensor(arrayOf(xMask), longArrayOf(1, 1, tX.toLong())),
                "sid" to longTensor(longArrayOf(SPEAKER_ID), longArrayOf(1)),
                "noise_scale_w" to floatTensor(arrayOf(floatArrayOf(noiseScaleW)), longArrayOf(1))
            )
        ).use { out ->
            return to2D(tensor(out, "logw"))[0]   // [1,1,t_x] → [t_x]
        }
    }

    /** flow：z_p / y_mask / sid → z。输入 [channel][yLengths]。 */
    fun runFlow(zP: Array<FloatArray>, yLengths: Int): Array<FloatArray> {
        val yMask = Array(1) { FloatArray(yLengths) { 1f } }
        session("flow").run(
            mapOf(
                "z_p" to floatTensor(zP, longArrayOf(1, HIDDEN_CHANNELS.toLong(), yLengths.toLong())),
                "y_mask" to floatTensor(yMask, longArrayOf(1, 1, yLengths.toLong())),
                "sid" to longTensor(longArrayOf(SPEAKER_ID), longArrayOf(1))
            )
        ).use { out ->
            return to2D(tensor(out, "z"))
        }
    }

    /** dec：z / sid → audio [time_out]（time_out = y_lengths × 256）。 */
    fun runDec(z: Array<FloatArray>, yLengths: Int): FloatArray {
        session("dec").run(
            mapOf(
                "z" to floatTensor(z, longArrayOf(1, HIDDEN_CHANNELS.toLong(), yLengths.toLong())),
                "sid" to longTensor(longArrayOf(SPEAKER_ID), longArrayOf(1))
            )
        ).use { out ->
            return to2D(tensor(out, "audio"))[0]   // [1,1,time_out] → [time_out]
        }
    }

    /** 释放所有 session 与 native 内存。 */
    fun close() {
        synchronized(sessionLock) {
            sessions.values.forEach {
                try { it.close() } catch (e: Exception) { Log.w(TAG, "关闭 session 异常", e) }
            }
            sessions.clear()
        }
    }

    // ── 张量构造 / 拆解 ────────────────────────────────

    private fun longTensor(data: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

    private fun floatTensor(data: Array<FloatArray>, shape: LongArray): OnnxTensor {
        // 展平 [channel][time] → 行主序一维
        val flat = FloatBuffer.allocate(data.sumOf { it.size })
        for (row in data) flat.put(row)
        flat.rewind()
        return OnnxTensor.createTensor(env, flat, shape)
    }

    /** 按名取输出张量，缺失即报错（输出名拼写错误应尽早暴露）。 */
    private fun tensor(result: OrtSession.Result, name: String): OnnxTensor =
        (result.get(name).orElse(null) as? OnnxTensor) ?: error("输出张量缺失: $name")

    /** 把输出张量（[1,C,T] 或 [1,1,T]）拆成 [C][T] 二维。 */
    private fun to2D(t: OnnxTensor): Array<FloatArray> {
        val shape = t.info.shape            // e.g. [1, 192, T]
        val channels = shape[1].toInt()
        val time = shape[2].toInt()
        val buf = t.floatBuffer
        return Array(channels) { c ->
            FloatArray(time).also { row ->
                buf.position(c * time)
                buf.get(row)
            }
        }
    }

    data class EncResult(
        val xEnc: Array<FloatArray>,
        val mP: Array<FloatArray>,
        val logsP: Array<FloatArray>,
        val xMask: FloatArray
    )
}
