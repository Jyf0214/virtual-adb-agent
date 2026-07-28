package com.virtual.adb.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试截图管理器
 *
 * 保存最近 N 张调试截图，暴露给 UI 层展示和操作。
 */
object DebugScreenshotManager {

    private const val MAX_SCREENSHOTS = 10

    data class ScreenshotInfo(
        val filePath: String,
        val fileName: String,
        val timestamp: Long,
        val sizeBytes: Long
    )

    private val _screenshots = MutableStateFlow<List<ScreenshotInfo>>(emptyList())
    val screenshots: StateFlow<List<ScreenshotInfo>> = _screenshots.asStateFlow()

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    /**
     * 生成带时间戳的文件名并保存截图
     *
     * @param dir 保存目录
     * @param imageData 图片字节数组（JPEG 或 PNG）
     * @return 保存后的文件路径，失败返回 null
     */
    fun saveScreenshot(dir: File, imageData: ByteArray): String? {
        return try {
            val timestamp = System.currentTimeMillis()
            val timeStr = dateFormat.format(Date(timestamp))

            // 检测图片格式：JPEG 文件头 FF D8 FF，否则按 PNG 处理
            val ext = if (imageData.size >= 3 &&
                imageData[0] == 0xFF.toByte() &&
                imageData[1] == 0xD8.toByte() &&
                imageData[2] == 0xFF.toByte()
            ) {
                "jpg"
            } else {
                "png"
            }
            val file = File(dir, "screencap_${timeStr}.$ext")
            file.writeBytes(imageData)

            val info = ScreenshotInfo(
                filePath = file.absolutePath,
                fileName = file.name,
                timestamp = timestamp,
                sizeBytes = bitmapData.size.toLong()
            )

            val current = _screenshots.value.toMutableList()
            current.add(info)

            // 超过上限时删除最旧的
            if (current.size > MAX_SCREENSHOTS) {
                val removed = current.removeAt(0)
                try {
                    File(removed.filePath).delete()
                } catch (_: Exception) {
                }
            }

            _screenshots.value = current
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 清空所有调试截图（删除文件 + 清空列表）
     */
    fun clearAll() {
        val files = _screenshots.value.toList()
        _screenshots.value = emptyList()
        // 后台删除文件
        CoroutineScope(Dispatchers.IO).launch {
            files.forEach { info ->
                try {
                    File(info.filePath).delete()
                } catch (_: Exception) {
                }
            }
        }
    }
}
