package com.virtual.adb.agent.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.lifecycle.AndroidViewModel
import com.virtual.adb.agent.AppLogger
import com.virtual.adb.agent.ResolutionMode
import com.virtual.adb.agent.RotationMode
import com.virtual.adb.agent.ScreenCaptureService
import com.virtual.adb.agent.ServerConfig
import com.virtual.adb.agent.TcpBridgeServer
import com.virtual.adb.agent.VirtualAdbApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主界面 ViewModel
 *
 * 管理权限状态、TCP 服务启停、屏幕捕捉授权等。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        const val REQUEST_SCREEN_CAPTURE = 1001
    }

    // ─── 状态 ──────────────────────────────────────────────────

    /** 无障碍服务是否已启用 */
    private val _a11yEnabled = MutableStateFlow(false)
    val a11yEnabled: StateFlow<Boolean> = _a11yEnabled.asStateFlow()

    /** 屏幕捕捉是否已授权 */
    private val _screenCaptureEnabled = MutableStateFlow(false)
    val screenCaptureEnabled: StateFlow<Boolean> = _screenCaptureEnabled.asStateFlow()

    /** TCP 服务器是否正在运行 */
    private val _tcpRunning = MutableStateFlow(false)
    val tcpRunning: StateFlow<Boolean> = _tcpRunning.asStateFlow()

    /** 局域网模式（绑定 0.0.0.0 允许外部设备接入） */
    private val _lanMode = MutableStateFlow(false)
    val lanMode: StateFlow<Boolean> = _lanMode.asStateFlow()

    /** TCP 服务器监听端口 */
    private val _tcpPort = MutableStateFlow(10000)
    val tcpPort: StateFlow<Int> = _tcpPort.asStateFlow()

    /** 屏幕捕捉服务是否正在运行 */
    private val _captureRunning = MutableStateFlow(false)
    val captureRunning: StateFlow<Boolean> = _captureRunning.asStateFlow()

    /** 日志消息 */
    private val _logMessage = MutableStateFlow("")
    val logMessage: StateFlow<String> = _logMessage.asStateFlow()

    /** TCP 日志列表 */
    val tcpLogs: StateFlow<List<TcpBridgeServer.LogEntry>> = tcpServer.logs

    /** TCP 启动错误 */
    val tcpStartError: StateFlow<String> = tcpServer.startError

    // ─── ServerConfig 状态（供 UI 直接读取） ────────────────

    val resolutionMode: StateFlow<ResolutionMode> = ServerConfig.resolutionMode
    val rotationMode: StateFlow<RotationMode> = ServerConfig.rotationMode
    val customWidth: StateFlow<Int> = ServerConfig.customWidth
    val customHeight: StateFlow<Int> = ServerConfig.customHeight

    // 截图性能配置
    val enableSmartScale: StateFlow<Boolean> = ServerConfig.enableSmartScale
    val smartScaleTargetWidth: StateFlow<Int> = ServerConfig.smartScaleTargetWidth
    val jpegQuality: StateFlow<Int> = ServerConfig.jpegQuality

    // 调试配置
    val enableDebugSave: StateFlow<Boolean> = ServerConfig.enableDebugSave
    val enableVerboseLog: StateFlow<Boolean> = ServerConfig.enableVerboseLog
    val enableChunkLog: StateFlow<Boolean> = ServerConfig.enableChunkLog

    // ─── MediaProjection 授权结果待处理 ──────────────────────
    private var pendingProjectionResultCode = 0
    private var pendingProjectionData: Intent? = null

    private val tcpServer get() = VirtualAdbApp.tcpServer

    init {
        checkA11yStatus()
        // 同步 TCP 服务器实际运行状态
        if (tcpServer.isRunning.value) {
            _tcpRunning.value = true
            _tcpPort.value = tcpServer.boundPort.value
        }
    }

    // ─── 无障碍服务 ──────────────────────────────────────────

    /**
     * 检查无障碍服务是否已启用
     */
    fun checkA11yStatus() {
        val context = getApplication<Application>()
        val enabled = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.contains("com.virtual.adb.agent") == true
        } catch (e: Exception) {
            AppLogger.e(TAG, "检查无障碍状态失败", e)
            false
        }
        _a11yEnabled.value = enabled
        // 不覆盖已有的日志消息
        if (_logMessage.value.isEmpty()) {
            _logMessage.value = if (enabled) "无障碍服务已就绪" else "无障碍服务未启用"
        }
    }

    /**
     * 跳转到系统无障碍设置页面
     */
    fun openA11ySettings(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    // ─── 屏幕捕捉 ──────────────────────────────────────────

    /**
     * 请求 MediaProjection 授权
     *
     * @param activity 用于启动系统授权对话框的 Activity
     */
    @Suppress("DEPRECATION")
    fun requestScreenCapturePermission(activity: ComponentActivity) {
        val projectionManager =
            activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        activity.startActivityForResult(intent, REQUEST_SCREEN_CAPTURE)
    }

    /**
     * 处理 MediaProjection 授权结果
     */
    fun handleScreenCaptureResult(resultCode: Int, data: Intent?) {
        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            pendingProjectionResultCode = resultCode
            pendingProjectionData = data
            _screenCaptureEnabled.value = true
            startScreenCapture()
            _logMessage.value = "屏幕捕捉已授权并启动"
        } else {
            _screenCaptureEnabled.value = false
            _logMessage.value = "屏幕捕捉授权被拒绝"
        }
    }

    /**
     * 启动屏幕捕捉服务
     */
    private fun startScreenCapture() {
        val context = getApplication<Application>()
        val intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = "com.virtual.adb.agent.action.START_CAPTURE"
            putExtra("result_code", pendingProjectionResultCode)
            putExtra("result_data", pendingProjectionData)
        }
        context.startForegroundService(intent)
        _captureRunning.value = true

        // 将服务引用注入 TCP 服务器
        // 注意：实际绑定需要在 ServiceConnection 中完成，这里简化处理
        _logMessage.value = "屏幕捕捉服务已启动"
    }

    /**
     * 停止屏幕捕捉服务
     */
    fun stopScreenCapture() {
        val context = getApplication<Application>()
        val intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = "com.virtual.adb.agent.action.STOP_CAPTURE"
        }
        context.startService(intent)
        _captureRunning.value = false
        _screenCaptureEnabled.value = false
        _logMessage.value = "屏幕捕捉已停止"
    }

    // ─── TCP 服务 ──────────────────────────────────────────

    /**
     * 切换 TCP 服务器运行状态
     */
    fun toggleTcpServer() {
        if (_tcpRunning.value) {
            tcpServer.stop()
            _tcpRunning.value = false
            _logMessage.value = "TCP 服务器已停止"
        } else {
            val host = if (_lanMode.value) "0.0.0.0" else "127.0.0.1"
            tcpServer.start(host)
            _tcpRunning.value = true
            _tcpPort.value = tcpServer.boundPort.value
            _logMessage.value = "TCP 服务器已启动，监听 $host:${_tcpPort.value}"
        }
    }

    fun toggleLanMode() {
        _lanMode.value = !_lanMode.value
    }

    /**
     * 生成测试连接命令
     */
    fun getTestCommand(): String {
        val port = _tcpPort.value
        return """echo '{"action":"ping"}' | nc 127.0.0.1 $port"""
    }

    /**
     * 生成 Python 测试脚本
     */
    fun getPythonTestScript(): String {
        val port = _tcpPort.value
        return """
import socket, json

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.connect(('127.0.0.1', $port))

# Ping 测试
s.send(json.dumps({"action": "ping"}) + b'\n')
print("Ping:", s.recv(4096).decode())

s.close()
        """.trimIndent()
    }

    /**
     * 清空 TCP 日志
     */
    fun clearTcpLogs() {
        tcpServer.clearLogs()
    }

    // ─── ServerConfig 修改方法 ───────────────────────────────

    /**
     * 设置分辨率返回模式
     */
    fun setResolutionMode(mode: ResolutionMode) {
        ServerConfig.resolutionMode.value = mode
    }

    /**
     * 设置截图旋转矫正模式
     */
    fun setRotationMode(mode: RotationMode) {
        ServerConfig.rotationMode.value = mode
    }

    /**
     * 设置自定义分辨率
     */
    fun setCustomResolution(width: Int, height: Int) {
        ServerConfig.customWidth.value = width
        ServerConfig.customHeight.value = height
    }

    // ─── 截图性能配置修改 ──────────────────────────────────

    /** 切换智能缩放 */
    fun toggleSmartScale() {
        ServerConfig.enableSmartScale.value = !ServerConfig.enableSmartScale.value
    }

    /** 设置智能缩放目标宽度 */
    fun setSmartScaleTargetWidth(width: Int) {
        ServerConfig.smartScaleTargetWidth.value = width
    }

    /** 设置 JPEG 压缩质量 */
    fun setJpegQuality(quality: Int) {
        ServerConfig.jpegQuality.value = quality.coerceIn(1, 100)
    }

    // ─── 调试配置修改 ──────────────────────────────────────

    /** 切换调试存图 */
    fun toggleDebugSave() {
        ServerConfig.enableDebugSave.value = !ServerConfig.enableDebugSave.value
    }

    /** 切换详细日志 */
    fun toggleVerboseLog() {
        ServerConfig.enableVerboseLog.value = !ServerConfig.enableVerboseLog.value
    }

    /** 切换分块传输日志 */
    fun toggleChunkLog() {
        ServerConfig.enableChunkLog.value = !ServerConfig.enableChunkLog.value
    }
}
