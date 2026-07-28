package com.virtual.adb.agent

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 分辨率返回模式
 */
enum class ResolutionMode {
    REAL_SYSTEM,     // 跟随系统真实分辨率
    FORCE_LANDSCAPE, // 强制横屏 (宽 > 高)
    FORCE_PORTRAIT,  // 强制竖屏 (宽 < 高)
    CUSTOM           // 自定义分辨率
}

/**
 * 截图旋转矫正模式
 */
enum class RotationMode {
    AUTO_SENSOR,     // 智能感应 (根据系统 Display.rotation)
    NONE,            // 不旋转 (原图)
    ROTATE_90,       // 强制 90°
    ROTATE_270       // 强制 270°
}

/**
 * 服务器全局配置单例
 *
 * UI 界面通过修改这些 StateFlow 来实时控制行为
 */
object ServerConfig {
    // ─── 分辨率配置 ─────────────────────────────────────────

    /** 分辨率返回模式 */
    val resolutionMode = MutableStateFlow(ResolutionMode.REAL_SYSTEM)

    /** 截图旋转矫正模式 */
    val rotationMode = MutableStateFlow(RotationMode.AUTO_SENSOR)

    /** 自定义分辨率 - 宽度 */
    val customWidth = MutableStateFlow(1920)

    /** 自定义分辨率 - 高度 */
    val customHeight = MutableStateFlow(1080)

    // ─── 截图性能配置 ───────────────────────────────────────

    /** 启用智能缩放（宽度超过目标值时等比缩放） */
    val enableSmartScale = MutableStateFlow(true)

    /** 智能缩放目标宽度 */
    val smartScaleTargetWidth = MutableStateFlow(1280)

    /** JPEG 压缩质量 (1-100) */
    val jpegQuality = MutableStateFlow(100)

    // ─── 调试配置 ───────────────────────────────────────────

    /** 启用调试存图（每次截图保存一份到本地磁盘） */
    val enableDebugSave = MutableStateFlow(true)

    /** 启用详细日志（打印截图尺寸、耗时等信息） */
    val enableVerboseLog = MutableStateFlow(true)

    /** 启用分块传输日志（打印每个分块的传输详情） */
    val enableChunkLog = MutableStateFlow(false)
}
