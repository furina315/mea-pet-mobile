package com.meapet.mobile.tts.model

import android.content.Context
import android.os.Build
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TTS 模型与 ONNX Runtime 原生库的下载 / 校验 / 加载管理。
 *
 * ## 分发模型（均不打进 APK，按需下载以减小体积）
 * - 4 个 ONNX（共 ~73MB）→ `filesDir/tts_model/`
 * - `libonnxruntime.so`（按设备 ABI，~19MB）→ `filesDir/tts_model/lib/<abi>/`
 *
 * ## 原生库加载
 * ONNX Java 绑定（libonnxruntime4j_jni.so 随 AAR 打包，体积很小）在初始化时需要
 * `libonnxruntime.so`。这里在首次推理前用 [ensureNativeLoaded] 通过 `System.load`
 * 显式加载下载好的完整版 .so——Android 动态链接器按 soname 缓存，后续绑定的
 * `loadLibrary("onnxruntime")` 会命中已加载的库。
 *
 * ## 配置
 * 远程地址经 BuildConfig 从 local.properties 注入到 AppConfig（`ttsModelBaseUrl`）。
 * 联调期可手动把文件推到 `filesDir/tts_model/` 跳过下载。
 */
class TtsModelManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsModelManager"
        private const val MODEL_DIR = "tts_model"

        /** 必需模型文件名（4 件齐全才算模型就绪）。 */
        val REQUIRED_MODEL_FILES = listOf("enc_p.onnx", "dp.onnx", "flow.onnx", "dec.onnx")

        /** 4 个模型的预期大小（字节），用于下载完整性粗校验。 */
        val EXPECTED_SIZES = mapOf(
            "enc_p.onnx" to 25_668_587L,
            "dp.onnx" to 2_714_999L,
            "flow.onnx" to 17_482_772L,
            "dec.onnx" to 28_977_164L
        )

        /** 原生库文件名与各 ABI 预期大小（onnxruntime-android 1.23.2 完整版）。 */
        const val NATIVE_LIB = "libonnxruntime.so"
        val NATIVE_LIB_SIZES = mapOf(
            "arm64-v8a" to 19_347_616L,
            "armeabi-v7a" to 13_992_328L,
            "x86_64" to 23_181_616L,
            "x86" to 22_761_828L
        )

        /** 当前设备首选 ABI。 */
        val deviceAbi: String
            get() = Build.SUPPORTED_ABIS.firstOrNull { it in NATIVE_LIB_SIZES } ?: "arm64-v8a"
    }

    /** 一个待下载文件：远程地址 + 落盘相对路径 + 预期大小（0=不校验）。 */
    data class ModelFile(val url: String, val relativePath: String, val expectedSize: Long = 0L)

    private val modelDir: File by lazy { File(context.filesDir, MODEL_DIR) }

    /** 原生库所在目录（`filesDir/tts_model/lib/<abi>/`）。 */
    private val nativeLibDir: File by lazy { File(modelDir, "lib/$deviceAbi") }
    private val nativeLibFile: File by lazy { File(nativeLibDir, NATIVE_LIB) }

    private val _state = MutableStateFlow<TtsModelState>(TtsModelState.NotDownloaded)
    val state: StateFlow<TtsModelState> = _state.asStateFlow()

    @Volatile
    private var nativeLoaded = false

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

    /** 模型 4 件是否已就绪。 */
    fun isModelReady(): Boolean =
        REQUIRED_MODEL_FILES.all { File(modelDir, it).let { f -> f.exists() && f.length() > 0 } }

    /** 原生库是否已下载。 */
    fun isNativeLibReady(): Boolean = nativeLibFile.exists() && nativeLibFile.length() > 0

    /** 全部就绪（模型 + 原生库）才算可用。 */
    fun isReady(): Boolean = isModelReady() && isNativeLibReady()

    /** 模型文件绝对路径（供 ONNX 引擎加载）。 */
    fun modelFile(name: String): File = File(modelDir, name)

    /** 启动/操作后按磁盘实际状态刷新状态流。 */
    fun refreshState() {
        _state.value = if (isReady()) TtsModelState.Ready else TtsModelState.NotDownloaded
    }

    /**
     * 由 baseUrl 生成下载清单（4 个模型 + 当前 ABI 的原生库）。baseUrl 为空返回空列表（未配置）。
     * 约定：远端模型与本地同名（`$baseUrl/enc_p.onnx`），原生库平铺为 `$baseUrl/libonnxruntime-<abi>.so`，
     * 按 [deviceAbi] 只下载匹配当前设备的那一份，落盘后改回标准名 `libonnxruntime.so` 供加载。
     */
    fun buildDownloadFiles(baseUrl: String): List<ModelFile> {
        if (baseUrl.isBlank()) return emptyList()
        val base = baseUrl.trimEnd('/')
        val models = REQUIRED_MODEL_FILES.map { name ->
            ModelFile(url = "$base/$name", relativePath = name, expectedSize = EXPECTED_SIZES[name] ?: 0L)
        }
        val native = ModelFile(
            url = "$base/libonnxruntime-$deviceAbi.so",
            relativePath = "lib/$deviceAbi/$NATIVE_LIB",
            expectedSize = NATIVE_LIB_SIZES[deviceAbi] ?: 0L
        )
        return models + native
    }

    /**
     * 确保原生库已加载（首次推理前调用）。幂等、线程安全。
     *
     * @throws UnsatisfiedLinkError 库未下载或加载失败
     */
    @Synchronized
    fun ensureNativeLoaded() {
        if (nativeLoaded) return
        val lib = nativeLibFile
        check(lib.exists()) { "ONNX Runtime 原生库未下载：${lib.absolutePath}" }
        System.load(lib.absolutePath)
        nativeLoaded = true
        Log.i(TAG, "ONNX Runtime 原生库已加载：${lib.absolutePath} ($deviceAbi)")
    }

    /** 顺序下载一组文件，逐个流式落盘并更新进度。已存在且校验通过的文件跳过（断点续传）。 */
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
                // 跳过已下载完成的（大小校验通过），重试时不全量重下
                if (isFileComplete(file)) {
                    Log.d(TAG, "跳过已存在：${file.relativePath}")
                    downloaded += file.expectedSize
                    continue
                }
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
                _state.value = TtsModelState.Error("下载完成但文件不完整")
            } else {
                Log.i(TAG, "模型下载完成，共 ${files.size} 个文件")
            }
        } catch (e: Exception) {
            Log.e(TAG, "模型下载失败", e)
            _state.value = TtsModelState.Error(e.message ?: "下载失败")
        }
    }

    /** 文件已存在且大小符合预期（配置了预期大小时）即视为已下载完成。 */
    private fun isFileComplete(file: ModelFile): Boolean {
        val target = File(modelDir, file.relativePath)
        if (!target.exists() || target.length() == 0L) return false
        // 配置了预期大小则严格比对；未配置则只要非空即视为完成
        return file.expectedSize <= 0 || target.length() == file.expectedSize
    }

    /** 流式下载单个文件到临时文件，完成后原子改名 + 大小校验。 */
    private suspend fun downloadOne(file: ModelFile, onProgress: (Long) -> Unit) {
        val target = File(modelDir, file.relativePath)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")

        val response = client.get(file.url)
        if (response.status.value !in 200..299) {
            throw IllegalStateException("HTTP ${response.status.value}：${file.relativePath}")
        }

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

        if (file.expectedSize > 0 && tmp.length() != file.expectedSize) {
            tmp.delete()
            throw IllegalStateException(
                "文件大小不符：${file.relativePath} 期望 ${file.expectedSize} 实得 ${tmp.length()}"
            )
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IllegalStateException("落盘失败：${file.relativePath}")
    }

    /** 删除模型与原生库，释放空间；调用方负责随后关闭语音开关。 */
    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        modelDir.deleteRecursively()
        refreshState()
        Log.i(TAG, "已删除 TTS 模型与原生库")
    }

    fun close() = client.close()
}
