package com.meapet.mobile.core

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.meapet.mobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志导出：抓取本进程自启动以来的 logcat，写入 cache，再拉起系统分享菜单。
 *
 * 用户可经分享把 .log 发给开发者排查问题。
 *
 * ## 隐私
 * - 只抓**本进程**日志（`--pid`），不碰其它应用；
 * - 项目日志本身不含对话内容等隐私（各模块刻意不打正文进 Logcat），故不做内容脱敏；
 * - 文件落在 cache/logs，分享后经 FileProvider 临时授权，不长期暴露。
 */
object LogExporter {

    private const val TAG = "LogExporter"
    private const val LOG_DIR = "logs"

    /**
     * 导出日志并拉起系统分享菜单。
     *
     * @return 是否成功（失败时调用方可提示用户）
     */
    suspend fun exportAndShare(context: Context): Boolean = withContext(Dispatchers.IO) {
        val file = try {
            collect(context)
        } catch (e: Exception) {
            Log.e(TAG, "日志抓取失败", e)
            return@withContext false
        }
        withContext(Dispatchers.Main) { share(context, file) }
        true
    }

    /** 抓取本进程自启动以来的全部 logcat，写入 cache/logs，返回文件。 */
    private fun collect(context: Context): File {
        val dir = File(context.cacheDir, LOG_DIR).apply { mkdirs() }
        // 清理过期日志，避免堆积
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(5)?.forEach { it.delete() }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "meapet-$stamp.log")

        val header = buildString {
            appendLine("MeaPet 日志导出")
            appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("─".repeat(40))
        }

        // 抓本进程自启动以来的全部日志（-d 立即返回非阻塞，--pid 限定本进程）
        val pid = android.os.Process.myPid()
        val process = ProcessBuilder(
            "logcat", "-d", "--pid=$pid", "-v", "threadtime"
        ).redirectErrorStream(true).start()

        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(header)
            process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                writer.write(line)
                writer.newLine()
            }
        }
        process.waitFor()
        return file
    }

    /** 经 FileProvider 拉起系统分享菜单。 */
    private fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "MeaPet 日志 ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享日志给开发者").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
