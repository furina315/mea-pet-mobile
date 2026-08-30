package com.meapet.mobile.core

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * LogExporter 的纯 JVM 单测（不依赖 Android 运行时）。
 *
 * 核心断言：tombstone protobuf 含 ≥0x80 的高位字节与内嵌换行符，写出后必须逐字节一致。
 * 历史上 writeTombstones() 经 BufferedWriter（字符流）写出，高位字节被替换成
 * U+FFFD（ef bf bd），解出的 pid/tid 全是垃圾值——此测试防止该回归。
 */
class LogExporterTest {

    @Test
    fun `tombstone protobuf 高位字节逐字节原样写出`() {
        // 伪造一段 tombstone proto：0x0A 是 field tag，高位字节与内嵌换行都要保住
        val proto = byteArrayOf(
            0x0A, 0x04, 0x80.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0x0A,
            0x0A, 0x02, 0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte(),
        )
        val out = ByteArrayOutputStream()
        writeTombstoneBytes(ByteArrayInputStream(proto), out)
        assertArrayEquals("protobuf 字节被改写", proto, out.toByteArray())
    }
}