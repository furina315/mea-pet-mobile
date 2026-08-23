package com.meapet.mobile.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
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
 * 日志导出：抓取 logcat + 本进程 native crash tombstone，写入 cache，拉起系统分享菜单。
 *
 * 用户可经分享把 .log 发给开发者排查问题。
 *
 * ## 内容
 * - 设备 / 版本头信息；
 * - **tombstone**：本进程历史 native 崩溃（段错误等）的退出原因与堆栈，
 *   经 [ActivityManager.getHistoricalProcessExitReasons] 读取（API 30+；低版本跳过）；
 * - **logcat**：全量缓冲区的应用日志（不限定 PID——TTS 等服务可能跑在独立进程，
 *   且便于看到崩溃前的完整上下文）。
 *
 * ## 隐私
 * 项目日志不打对话正文；tombstone 仅含本包名进程的崩溃记录。文件经 FileProvider 临时授权分享。
 */
object LogExporter {

    private const val TAG = "LogExporter"
    private const val LOG_DIR = "logs"

    /**
     * 导出日志并拉起系统分享菜单。
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

    /** 抓取日志 + tombstone，写入 cache/logs，返回文件。 */
    private fun collect(context: Context): File {
        val dir = File(context.cacheDir, LOG_DIR).apply { mkdirs() }
        // 清理过期日志，避免堆积
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(5)?.forEach { it.delete() }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "meapet-$stamp.log")

        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(header())
            writer.newLine()
            writeTombstones(context, writer)
            writeLogcat(writer)
        }
        return file
    }

    private fun header(): String = buildString {
        appendLine("MeaPet 日志导出")
        appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("═".repeat(50))
    }

    /** 写入本进程历史 native crash 的 tombstone（API 30+）。 */
    private fun writeTombstones(context: Context, writer: java.io.BufferedWriter) {
        writer.write("■ Native Crash (tombstone)")
        writer.newLine()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            writer.write("（系统版本低于 Android 11，不支持读取历史崩溃记录）")
            writer.newLine()
            writer.newLine()
            return
        }
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            // packageName=null+pid=0+maxNum：取本进程全部历史退出原因
            val reasons = am.getHistoricalProcessExitReasons(null, 0, 0)
            val crashes = reasons?.filter {
                it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
            } ?: emptyList()

            if (crashes.isEmpty()) {
                writer.write("（无 native 崩溃记录）")
                writer.newLine()
            } else {
                crashes.forEach { info ->
                    writer.write("── 崩溃于 ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))} ──")
                    writer.newLine()
                    writer.write("进程: ${info.processName}  重要性: ${info.importance}")
                    writer.newLine()
                    try {
                        info.traceInputStream?.bufferedReader()?.use { trace ->
                            trace.forEachLine { writer.write(it); writer.newLine() }
                        } ?: run { writer.write("（无堆栈详情）"); writer.newLine() }
                    } catch (e: Exception) {
                        writer.write("（堆栈读取失败: ${e.message}）")
                        writer.newLine()
                    }
                }
            }
        } catch (e: Exception) {
            writer.write("（tombstone 读取失败: ${e.message}）")
            writer.newLine()
        }
        writer.newLine()
        writer.write("═".repeat(50))
        writer.newLine()
    }

    /** 写入 logcat 全量缓冲区（不限定 PID，覆盖服务进程与崩溃前上下文）。 */
    private fun writeLogcat(writer: java.io.BufferedWriter) {
        writer.write("■ Logcat")
        writer.newLine()
        try {
            // -d 立即返回（非阻塞）；-v threadtime 带时间戳与线程
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime")
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                writer.write(line)
                writer.newLine()
            }
            process.waitFor()
        } catch (e: Exception) {
            writer.write("（logcat 抓取失败: ${e.message}）")
            writer.newLine()
        }
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
