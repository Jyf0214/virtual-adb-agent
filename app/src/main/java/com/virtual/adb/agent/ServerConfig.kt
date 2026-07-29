package com.virtual.adb.agent

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 分辨率返回模式
 */
enum class ResolutionMode {
    REAL_SYSTEM,
    FORCE_LANDSCAPE,
    FORCE_PORTRAIT,
    CUSTOM
}

/**
 * 截图旋转矫正模式
 */
enum class RotationMode {
    AUTO_SENSOR,
    NONE,
    ROTATE_90,
    ROTATE_270
}

/**
 * 服务器全局配置单例
 *
 * UI 界面通过修改这些 StateFlow 来实时控制行为。
 * 通过 [init] 从 SharedPreferences 加载，修改后调用 [save] 持久化。
 */
object ServerConfig {

    private const val PREFS_NAME = "server_config"
    private lateinit var prefs: SharedPreferences
    private var isLoaded = false

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

    /** 启用智能缩放 */
    val enableSmartScale = MutableStateFlow(true)

    /** 智能缩放目标宽度 */
    val smartScaleTargetWidth = MutableStateFlow(1280)

    /** JPEG 压缩质量 (1-100) */
    val jpegQuality = MutableStateFlow(100)

    // ─── 调试配置 ───────────────────────────────────────────

    /** 开发者模式（控制调试功能可见性） */
    val isDeveloperMode = MutableStateFlow(false)

    /** 调试存图（每次截图保存一份到本地磁盘） */
    val enableDebugSave = MutableStateFlow(false)

    /** 详细日志（打印截图尺寸、耗时等信息） */
    val enableVerboseLog = MutableStateFlow(false)

    /** 分块传输日志（打印每个分块的传输详情） */
    val enableChunkLog = MutableStateFlow(false)

    // ─── 应用状态 ───────────────────────────────────────────

    /** 是否首次启动引导已完成 */
    var isFirstLaunchDone = false
        private set

    // ─── 持久化 ─────────────────────────────────────────────

    /**
     * 从 SharedPreferences 加载配置。
     * 应在 Application.onCreate() 中调用。
     */
    fun init(context: Context) {
        if (isLoaded) return
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        resolutionMode.value = tryParseEnum(
            prefs.getString("resolution_mode", null),
            ResolutionMode.REAL_SYSTEM
        )
        rotationMode.value = tryParseEnum(
            prefs.getString("rotation_mode", null),
            RotationMode.AUTO_SENSOR
        )
        customWidth.value = prefs.getInt("custom_width", 1920).coerceAtLeast(320)
        customHeight.value = prefs.getInt("custom_height", 1080).coerceAtLeast(240)
        enableSmartScale.value = prefs.getBoolean("smart_scale", true)
        smartScaleTargetWidth.value = prefs.getInt("smart_scale_width", 1280).coerceIn(320, 3840)
        jpegQuality.value = prefs.getInt("jpeg_quality", 100).coerceIn(1, 100)
        isDeveloperMode.value = prefs.getBoolean("developer_mode", false)
        enableDebugSave.value = prefs.getBoolean("debug_save", false)
        enableVerboseLog.value = prefs.getBoolean("verbose_log", false)
        enableChunkLog.value = prefs.getBoolean("chunk_log", false)
        isFirstLaunchDone = prefs.getBoolean("first_launch_done", false)

        isLoaded = true
    }

    /**
     * 将当前所有配置持久化到 SharedPreferences。
     * ViewModel 中每个 setter 修改配置后应调用此方法。
     */
    fun save() {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .putString("resolution_mode", resolutionMode.value.name)
            .putString("rotation_mode", rotationMode.value.name)
            .putInt("custom_width", customWidth.value)
            .putInt("custom_height", customHeight.value)
            .putBoolean("smart_scale", enableSmartScale.value)
            .putInt("smart_scale_width", smartScaleTargetWidth.value)
            .putInt("jpeg_quality", jpegQuality.value)
            .putBoolean("developer_mode", isDeveloperMode.value)
            .putBoolean("debug_save", enableDebugSave.value)
            .putBoolean("verbose_log", enableVerboseLog.value)
            .putBoolean("chunk_log", enableChunkLog.value)
            .apply()
    }

    /**
     * 标记首次启动引导已完成
     */
    fun markFirstLaunchDone() {
        isFirstLaunchDone = true
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean("first_launch_done", true).apply()
        }
    }

    /**
     * 重置所有配置为默认值（仅内存，调用 [save] 后才会覆写持久化）
     */
    fun resetToDefaults() {
        resolutionMode.value = ResolutionMode.REAL_SYSTEM
        rotationMode.value = RotationMode.AUTO_SENSOR
        customWidth.value = 1920
        customHeight.value = 1080
        enableSmartScale.value = true
        smartScaleTargetWidth.value = 1280
        jpegQuality.value = 100
        isDeveloperMode.value = false
        enableDebugSave.value = false
        enableVerboseLog.value = false
        enableChunkLog.value = false
    }

    private inline fun <reified T : Enum<T>> tryParseEnum(value: String?, default: T): T {
        if (value == null) return default
        return try {
            enumValueOf<T>(value)
        } catch (_: IllegalArgumentException) {
            default
        }
    }
}
