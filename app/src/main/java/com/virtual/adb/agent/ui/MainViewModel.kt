package com.virtual.adb.agent.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.AndroidViewModel
import com.virtual.adb.agent.ScreenCaptureService
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

    /** Logcat 原生日志 */
    val logcatLogs: StateFlow<List<String>> = logcatReader.logs
    val logcatRunning: StateFlow<Boolean> = logcatReader.isRunning

    /** MediaProjection 授权结果待处理 */
    private var pendingProjectionResultCode = 0
    private var pendingProjectionData: Intent? = null

    private val tcpServer get() = VirtualAdbApp.tcpServer
    private val logcatReader get() = VirtualAdbApp.logcatReader

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
            Log.e(TAG, "检查无障碍状态失败", e)
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

    // ─── Logcat 控制 ──────────────────────────────────────────

    fun toggleLogcat() {
        if (logcatReader.isRunning.value) {
            logcatReader.stop()
        } else {
            logcatReader.start()
        }
    }

    fun clearLogcatLogs() {
        logcatReader.clear()
    }
}
