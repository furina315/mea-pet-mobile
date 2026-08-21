package com.meapet.mobile.tts.model

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TTS 模型与日语词典的下载 / 校验 / 删除管理。
 *
 * ## 分发模型
 * 4 个 ONNX（共 ~73MB）**不打进 APK**，按需从远程（GitHub Releases 等）下载到
 * `filesDir/tts_model/`；日语词典（naist-jdic ~102MB，仅日语需要）由 piper-plus
 * 下载器单独下载到 `filesDir/open_jtalk_dic/`。
 *
 * ## 目录布局
 * ```
 * filesDir/tts_model/                                # 必需，4 件齐全才 Ready
 * ├── enc_p.onnx / dp.onnx / flow.onnx / dec.onnx
 * filesDir/open_jtalk_dic/                           # 日语词典（可选，仅日语需要）
 * ```
 *
 * ## 现状
 * 远程地址经 BuildConfig 从 local.properties 注入到 AppConfig（`ttsModelBaseUrl` /
 * `ttsJaDicUrl`），缺省为空表示未配置。联调期可手动把 onnx 推到 `filesDir/tts_model/` 跳过下载。
 */
class TtsModelManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsModelManager"
        private const val MODEL_DIR = "tts_model"
        // piper-plus 词典固定位于 filesDir/open_jtalk_dic/（其下载器/资产加载都用这个相对 filesDir 的路径）
        private const val DIC_DIR = "open_jtalk_dic"

        /** 必需模型文件名（4 件齐全才算模型就绪）。 */
        val REQUIRED_MODEL_FILES = listOf("enc_p.onnx", "dp.onnx", "flow.onnx", "dec.onnx")

        /** 4 个模型的预期大小（字节），用于下载完整性粗校验。 */
        val EXPECTED_SIZES = mapOf(
            "enc_p.onnx" to 25_668_587L,
            "dp.onnx" to 2_714_999L,
            "flow.onnx" to 17_482_772L,
            "dec.onnx" to 28_977_164L
        )
    }

    /** 一个待下载文件：远程地址 + 落盘相对路径 + 预期大小（0=不校验）。 */
    data class ModelFile(val url: String, val relativePath: String, val expectedSize: Long = 0L)

    private val modelDir: File by lazy { File(context.filesDir, MODEL_DIR) }
    private val dicDir: File by lazy { File(context.filesDir, DIC_DIR) }

    private val _state = MutableStateFlow<TtsModelState>(TtsModelState.NotDownloaded)
    val state: StateFlow<TtsModelState> = _state.asStateFlow()

    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = null   // 大文件不卡总时长（null = 无上限）
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
    }

    init {
        refreshState()
    }

    /** 模型 4 件是否已就绪（同步判断，供开关 gating）。 */
    fun isModelReady(): Boolean =
        REQUIRED_MODEL_FILES.all { File(modelDir, it).let { f -> f.exists() && f.length() > 0 } }

    /** 日语词典是否已下载（open_jtalk_dic 目录内含 sys.dic 即视为就绪）。 */
    fun isDicReady(): Boolean = File(dicDir, "sys.dic").exists()

    /** 模型文件绝对路径（供 ONNX 引擎加载）。 */
    fun modelFile(name: String): File = File(modelDir, name)

    /** 词典目录（`filesDir/open_jtalk_dic/`，piper `fromPath` 直用）。 */
    fun dictionaryDir(): File = dicDir

    /** 删除日语词典（保留模型）。 */
    suspend fun deleteDic() = withContext(Dispatchers.IO) {
        dicDir.deleteRecursively()
    }

    /**
     * 由 baseUrl 生成 4 个模型的下载清单。baseUrl 为空返回空列表（未配置）。
     * 约定：远端文件与本地同名，即 `$baseUrl/enc_p.onnx` 等。
     */
    fun buildModelFiles(baseUrl: String): List<ModelFile> {
        if (baseUrl.isBlank()) return emptyList()
        val base = baseUrl.trimEnd('/')
        return REQUIRED_MODEL_FILES.map { name ->
            ModelFile(
                url = "$base/$name",
                relativePath = name,
                expectedSize = EXPECTED_SIZES[name] ?: 0L
            )
        }
    }

    /** 启动/操作后按磁盘实际状态刷新状态流。 */
    fun refreshState() {
        _state.value = if (isModelReady()) TtsModelState.Ready else TtsModelState.NotDownloaded
    }

    /**
     * 顺序下载一组文件，逐个流式落盘并更新进度。
     *
     * 全部成功且模型校验通过 → [TtsModelState.Ready]；任一失败 → [TtsModelState.Error]。
     * 失败时已下载的部分文件保留，下次重试会覆盖重写（不追求字节级断点续传，GitHub Releases
     * 单文件不大，重下成本可接受）。
     */
    suspend fun download(files: List<ModelFile>) = withContext(Dispatchers.IO) {
        if (files.isEmpty()) {
            _state.value = TtsModelState.Error("未配置模型下载地址")
            return@withContext
        }
        modelDir.mkdirs()
        val totalBytes = files.sumOf { it.expectedSize }.takeIf { it > 0 }
        var downloaded = 0L
        try {
            for (file in files) {
                _state.value = TtsModelState.Downloading(
                    progress = if (totalBytes != null) downloaded.toFloat() / totalBytes else 0f,
                    currentFile = file.relativePath
                )
                downloadOne(file) { readBytes ->
                    if (totalBytes != null) {
                        _state.value = TtsModelState.Downloading(
                            progress = (downloaded + readBytes).toFloat() / totalBytes,
                            currentFile = file.relativePath
                        )
                    }
                }
                downloaded += file.expectedSize
            }
            refreshState()
            if (_state.value != TtsModelState.Ready) {
                _state.value = TtsModelState.Error("下载完成但模型文件不完整")
            } else {
                Log.i(TAG, "模型下载完成，共 ${files.size} 个文件")
            }
        } catch (e: Exception) {
            Log.e(TAG, "模型下载失败", e)
            _state.value = TtsModelState.Error(e.message ?: "下载失败")
        }
    }

    /** 流式下载单个文件到临时文件，完成后原子改名 + 大小校验。 */
    private suspend fun downloadOne(file: ModelFile, onProgress: (Long) -> Unit) {
        val target = File(modelDir, file.relativePath)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")

        val response = client.get(file.url)
        val statusOk = response.status.value in 200..299
        if (!statusOk) throw IllegalStateException("HTTP ${response.status.value}：${file.relativePath}")

        val channel = response.bodyAsChannel()
        val buffer = ByteArray(64 * 1024)
        tmp.outputStream().buffered().use { out ->
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read > 0) {
                    out.write(buffer, 0, read)
                    onProgress(read.toLong())
                }
            }
        }

        // 大小校验：配置了预期大小且不一致 → 视为损坏
        if (file.expectedSize > 0 && tmp.length() != file.expectedSize) {
            tmp.delete()
            throw IllegalStateException(
                "文件大小不符：${file.relativePath} 期望 ${file.expectedSize} 实得 ${tmp.length()}"
            )
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IllegalStateException("落盘失败：${file.relativePath}")
    }

    /** 删除模型与词典，释放空间；调用方负责随后关闭语音开关。 */
    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        modelDir.deleteRecursively()
        dicDir.deleteRecursively()
        refreshState()
        Log.i(TAG, "已删除 TTS 模型与词典")
    }

    fun close() = client.close()
}
