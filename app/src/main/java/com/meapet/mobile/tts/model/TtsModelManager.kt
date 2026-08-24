package com.meapet.mobile.tts.model

import android.content.Context
import android.net.Uri
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
import java.util.zip.ZipInputStream

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

    /** 下载重入锁：防止并发下载写同一批 .part 临时文件。 */
    private val downloadLock = kotlinx.coroutines.sync.Mutex()

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

    /**
     * 顺序下载一组文件，逐个流式落盘并更新进度。已存在且校验通过的文件跳过（断点续传）。
     *
     * 重入保护：正在下载时再次调用直接忽略，避免两个下载协程写同一批 .part 临时文件。
     * 用协程 [kotlinx.coroutines.sync.Mutex] 而非 `ReentrantLock`：后者是线程绑定锁，
     * 而本函数内 `downloadOne` 有挂起点（网络 IO），协程恢复可能切到另一个 IO 线程，
     * 届时 `finally { unlock() }` 会在非持锁线程执行 → `IllegalMonitorStateException`。
     */
    suspend fun download(files: List<ModelFile>) = withContext(Dispatchers.IO) {
        if (!downloadLock.tryLock()) {
            Log.w(TAG, "正在下载中，忽略重复调用")
            return@withContext
        }
        try {
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
        } finally {
            // tryLock 成功才走到这里；unlock 需持有锁（协程 Mutex 不绑定线程）
            try { downloadLock.unlock() } catch (_: IllegalStateException) {}
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

    /**
     * 从本地 zip 资源包手动导入（绕过 GitHub 下载，供网络受限用户使用）。
     *
     * 约定格式：zip 内**平铺无目录**——
     * - 4 个 ONNX：`enc_p.onnx` / `dp.onnx` / `flow.onnx` / `dec.onnx`；
     * - 各 ABI 的 ONNX Runtime 原生库：`libonnxruntime-<abi>.so`（下载约定同名）。
     *
     * 兼容打包时误嵌套子文件夹的情况：根级没有资源文件时，自动到子目录查找
     * （最多 2 层），取第一个命中的资源所在目录作为基准，其余目录忽略。
     *
     * 导入规则：**ONNX 全导入**；**so 按当前设备 ABI（[deviceAbi]）选择性导入**，
     * 只落盘匹配的那一份到 `lib/<abi>/libonnxruntime.so`，其余 ABI 的 so 跳过。
     *
     * 逐文件 `.part` 临时写盘后原子改名（与 [download] 一致，中断不留半文件）；
     * 完成后 [refreshState]，缺件/失败进 [TtsModelState.Error] 并列出缺项。
     */
    suspend fun importFromZip(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        _state.value = TtsModelState.Importing
        try {
            val resolver = context.contentResolver
            val stream = resolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("无法读取所选文件"))

            // 第一遍：列出全部条目，确定资源所在基准目录（根级优先，最多下探 2 层）
            val entries = ZipInputStream(stream).use { zis ->
                buildList {
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (!entry.isDirectory) add(entry.name)
                        zis.closeEntry()
                    }
                }
            }
            if (entries.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("zip 为空或无法解析"))
            }
            val baseDir = resolveBaseDir(entries)   // "" = 根级平铺
            val resolve = { name: String ->
                val rel = name.removePrefix(if (baseDir.isEmpty()) "" else "$baseDir/")
                if (rel.contains('/')) null else rel
            }

            // 写入目标：先清理旧文件再解压，防止残留半套模型/库
            modelDir.mkdirs()
            nativeLibDir.mkdirs()
            modelDir.listFiles()?.forEach { if (it.isFile) it.delete() }
            nativeLibDir.listFiles()?.forEach { if (it.isFile) it.delete() }

            val seenModels = mutableSetOf<String>()
            var seenNativeForDevice = false
            val maxEntrySize = 256L * 1024 * 1024   // 单条目上限（zip 炸弹粗防御）

            resolver.openInputStream(uri).use { input ->
                val zis = ZipInputStream(input)
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory) { zis.closeEntry(); continue }
                    val rel = resolve(name) ?: run { zis.closeEntry(); continue }

                    when {
                        rel in REQUIRED_MODEL_FILES -> {
                            writeEntry(zis, File(modelDir, rel), maxEntrySize, buf)
                            seenModels.add(rel)
                        }
                        else -> {
                            val abi = abiFromNativeName(rel)
                            // 只导入当前设备 ABI 的原生库
                            if (abi != null && abi == deviceAbi) {
                                writeEntry(zis, nativeLibFile, maxEntrySize, buf)
                                seenNativeForDevice = true
                            }
                            // 其他 ABI 的 so 或未知条目：跳过不解压
                        }
                    }
                    zis.closeEntry()
                }
            }

            val missing = REQUIRED_MODEL_FILES.filterNot { it in seenModels }
            refreshState()
            when {
                _state.value == TtsModelState.Ready -> {
                    Log.i(TAG, "zip 导入完成：${seenModels.size} 个 onnx，native=$seenNativeForDevice")
                    Result.success("导入成功")
                }
                missing.isNotEmpty() -> {
                    val msg = "资源包缺少模型：${missing.joinToString("、")}"
                    _state.value = TtsModelState.Error(msg)
                    Result.failure(IllegalStateException(msg))
                }
                !seenNativeForDevice -> {
                    val msg = "资源包缺少当前设备（$deviceAbi）的 native 库 libonnxruntime.so"
                    _state.value = TtsModelState.Error(msg)
                    Result.failure(IllegalStateException(msg))
                }
                else -> {
                    val msg = "导入失败：文件不完整"
                    _state.value = TtsModelState.Error(msg)
                    Result.failure(IllegalStateException(msg))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "zip 导入失败", e)
            _state.value = TtsModelState.Error(e.message ?: "导入失败")
            Result.failure(e)
        }
    }

    /**
     * 从全部条目里确定资源所在基准目录：根级有资源文件（onnx 或 native so）就用 ""，
     * 否则从第一层子目录开始，最多下探 2 层，取第一个命中的目录；找不到返回 ""。
     */
    private fun resolveBaseDir(entries: List<String>): String {
        val candidateDirs = mutableListOf("")
        entries.map { it.substringBefore('/') }.filter { it.isNotEmpty() }.toSet()
            .forEach { candidateDirs.add(it) }
        // 第一层子目录的子目录（最多 2 层）
        entries.mapNotNull { e ->
            val parts = e.split('/')
            if (parts.size >= 3 && parts[0].isNotEmpty()) parts[0] + "/" + parts[1] else null
        }.toSet().forEach { candidateDirs.add(it) }

        for (dir in candidateDirs) {
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            val hasResource = entries.any { name ->
                val rel = name.removePrefix(prefix)
                !rel.contains('/') &&
                    (rel in REQUIRED_MODEL_FILES || abiFromNativeName(rel) != null)
            }
            if (hasResource) return dir
        }
        return ""
    }

    /**
     * 从 `libonnxruntime-<abi>.so` 解析 ABI；非 native 库名（含裸 `libonnxruntime.so`，无 ABI 信息）返回 null。
     * 例：`libonnxruntime-arm64-v8a.so` → `arm64-v8a`。
     */
    private fun abiFromNativeName(name: String): String? {
        // 仅当「不带 ABI 后缀」时无 ABI 可解析；带后缀的文件名不以裸 NATIVE_LIB 结尾
        if (name == NATIVE_LIB) return null
        if (!name.startsWith("libonnxruntime-")) return null
        val abi = name.removePrefix("libonnxruntime-").removeSuffix(".so")
        return abi.takeIf { it in NATIVE_LIB_SIZES }
    }

    /** 把当前 zip entry 逐块写入目标文件（`.part` 临时文件 + 原子改名）。 */
    private fun writeEntry(zis: ZipInputStream, target: File, maxSize: Long, buf: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.outputStream().buffered().use { out ->
            var total = 0L
            while (true) {
                val n = zis.read(buf, 0, buf.size)
                if (n < 0) break
                total += n
                if (total > maxSize) throw IllegalStateException("文件过大：${target.name}")
                out.write(buf, 0, n)
            }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IllegalStateException("落盘失败：${target.name}")
    }

    /** 删除模型与原生库，释放空间；调用方负责随后关闭语音开关。 */
    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        modelDir.deleteRecursively()
        refreshState()
        Log.i(TAG, "已删除 TTS 模型与原生库")
    }

    fun close() = client.close()
}
