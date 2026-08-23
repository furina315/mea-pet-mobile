package com.meapet.mobile.tts.model

/**
 * TTS 模型（及日语词典）的下载状态机。
 *
 * - [NotDownloaded] 未下载：语音开关置灰，仅显示「下载」入口
 * - [Downloading] 下载中：[progress] 为 0f~1f 总进度
 * - [Ready] 就绪：语音功能开放
 * - [Error] 失败：展示原因，可重试
 */
sealed interface TtsModelState {
    data object NotDownloaded : TtsModelState
    data class Downloading(val progress: Float, val currentFile: String) : TtsModelState
    data object Ready : TtsModelState
    data class Error(val message: String) : TtsModelState
}
