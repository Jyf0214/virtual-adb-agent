package com.virtual.adb.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.SocketException

/**
 * TCP 桥接服务器
 *
 * 监听 127.0.0.1:10000，接收 JSON 命令并分发到
 * AccessibilityBridgeService 或 ScreenCaptureService 执行。
 */
class TcpBridgeServer(
    private val port: Int = 10000,
    private val host: String = "127.0.0.1"
) {

    companion object {
        private const val TAG = "TcpBridgeServer"
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_LOG_ENTRIES = 100
    }

    /** 日志条目 */
    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val direction: String,  // "→" 收到, "←" 发送
        val client: String,
        val content: String
    )

    /** 服务运行状态 */
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** 当前连接的客户端数量 */
    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount.asStateFlow()

    /** TCP 服务器绑定的端口（支持动态端口时使用） */
    private val _boundPort = MutableStateFlow(port)
    val boundPort: StateFlow<Int> = _boundPort.asStateFlow()

    /** 日志列表 */
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    /** 启动错误信息 */
    private val _startError = MutableStateFlow("")
    val startError: StateFlow<String> = _startError.asStateFlow()

    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    /** 无障碍服务引用（由 MainActivity 注入） */
    var accessibilityService: AccessibilityBridgeService? = null

    /** 屏幕捕捉服务引用（由 MainActivity 注入） */
    var screenCaptureService: ScreenCaptureService? = null

    /**
     * 启动 TCP 服务器
     */
    fun start() {
        // 单例保护：如果已有 ServerSocket 且未关闭，直接返回
        val existing = serverSocket
        if (existing != null && existing.isBound && !existing.isClosed) {
            Log.w(TAG, "服务器已在运行中（端口 ${existing.localPort}），跳过重复启动")
            _isRunning.value = true
            return
        }

        // 先清理旧实例
        stopInternal()

        _startError.value = "正在启动..."
        Log.i(TAG, "尝试启动 TCP 服务器，端口: $port")

        serverJob = serverScope.launch {
            try {
                // 在 IO 线程中绑定 IPv4 地址，开启端口复用
                withContext(Dispatchers.IO) {
                    val inetAddress = java.net.InetAddress.getByName("127.0.0.1")
                    val socket = ServerSocket()
                    socket.reuseAddress = true
                    socket.bind(java.net.InetSocketAddress(inetAddress, port))
                    serverSocket = socket
                    Log.i(TAG, "已绑定 127.0.0.1:$port，reuseAddress=true")
                }

                val boundAddr = serverSocket!!.inetAddress?.hostAddress ?: "unknown"
                val boundPort = serverSocket!!.localPort
                _boundPort.value = boundPort
                _isRunning.value = true
                _startError.value = ""
                Log.i(TAG, "TCP 服务器启动成功，监听 $boundAddr:$boundPort")

                while (isActive) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        Log.i(TAG, "新客户端连接: ${clientSocket.inetAddress}")
                        appendLog("→", "${clientSocket.inetAddress.hostAddress}:${clientSocket.port}", "客户端已连接")

                        launch {
                            handleClient(clientSocket)
                        }
                    } catch (e: SocketException) {
                        if (isActive) {
                            Log.e(TAG, "Accept 异常", e)
                        }
                    }
                }
            } catch (e: java.net.BindException) {
                val msg = "端口绑定失败: ${e.message}"
                Log.e(TAG, "TCP 服务器启动失败", e)
                _isRunning.value = false
                _startError.value = msg
            } catch (e: java.net.SocketException) {
                val msg = "Socket 错误: ${e.message}"
                Log.e(TAG, "TCP 服务器启动失败", e)
                _isRunning.value = false
                _startError.value = msg
            } catch (e: SecurityException) {
                val msg = "安全权限不足: ${e.message}"
                Log.e(TAG, "TCP 服务器启动失败", e)
                _isRunning.value = false
                _startError.value = msg
            } catch (e: Exception) {
                val msg = "启动失败: ${e.javaClass.simpleName} - ${e.message}"
                Log.e(TAG, "TCP 服务器启动失败", e)
                _isRunning.value = false
                _startError.value = msg
            }
        }
    }

    /**
     * 停止 TCP 服务器（公开接口）
     */
    fun stop() {
        stopInternal()
        _startError.value = ""
        Log.i(TAG, "TCP 服务器已停止")
    }

    /**
     * 内部停止逻辑
     */
    private fun stopInternal() {
        _isRunning.value = false
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭 ServerSocket 异常", e)
        }
        serverSocket = null
        _clientCount.value = 0
    }

    // ─── 客户端处理 ──────────────────────────────────────────

    private suspend fun handleClient(socket: java.net.Socket) {
        _clientCount.value = _clientCount.value + 1
        val clientAddr = "${socket.inetAddress.hostAddress}:${socket.port}"

        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val outputStream = socket.getOutputStream()

            appendLog("→", clientAddr, "客户端已连接")

            while (socket.isConnected && !socket.isClosed) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue

                Log.d(TAG, "收到命令: $line")
                appendLog("→", clientAddr, line)

                val response = processCommand(line)
                Log.d(TAG, "发送响应: $response")
                appendLog("←", clientAddr, response)

                withContext(Dispatchers.IO) {
                    outputStream.write((response + "\n").toByteArray())
                    outputStream.flush()
                }
            }
        } catch (e: SocketException) {
            appendLog("✗", clientAddr, "连接断开: ${e.message}")
            Log.d(TAG, "客户端断开连接: ${socket.inetAddress}")
        } catch (e: Exception) {
            appendLog("✗", clientAddr, "异常: ${e.message}")
            Log.e(TAG, "处理客户端异常", e)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
            _clientCount.value = (_clientCount.value - 1).coerceAtLeast(0)
            Log.d(TAG, "客户端处理结束，当前连接数: ${_clientCount.value}")
        }
    }

    /**
     * 添加日志条目
     */
    private fun appendLog(direction: String, client: String, content: String) {
        val entry = LogEntry(direction = direction, client = client, content = content)
        val current = _logs.value.toMutableList()
        current.add(entry)
        if (current.size > MAX_LOG_ENTRIES) {
            current.removeAt(0)
        }
        _logs.value = current
    }

    /**
     * 清空日志
     */
    fun clearLogs() {
        _logs.value = emptyList()
    }

    // ─── 命令解析与分发 ──────────────────────────────────────

    /**
     * 解析并执行 JSON 命令
     */
    private suspend fun processCommand(rawJson: String): String {
        return try {
            val json = JSONObject(rawJson)
            val action = json.optString("action", "")

            when (action) {
                "click" -> handleClick(json)
                "swipe" -> handleSwipe(json)
                "screencap" -> handleScreencap(json)
                "ping" -> handlePing()
                else -> errorResponse("unknown action: $action")
            }
        } catch (e: JSONException) {
            errorResponse("invalid JSON: ${e.message}")
        } catch (e: Exception) {
            errorResponse("command error: ${e.message}")
        }
    }

    /**
     * 处理点击命令
     *
     * 请求: {"action": "click", "x": 500, "y": 300}
     */
    private fun handleClick(json: JSONObject): String {
        val service = accessibilityService
            ?: return errorResponse("accessibility service not connected")

        if (!service.isActive()) {
            return errorResponse("accessibility service not active")
        }

        val x = json.optDouble("x", Double.NaN).toFloat()
        val y = json.optDouble("y", Double.NaN).toFloat()

        if (x.isNaN() || y.isNaN()) {
            return errorResponse("missing or invalid x/y coordinates")
        }

        return service.injectClick(x, y)
    }

    /**
     * 处理滑动命令
     *
     * 请求: {"action": "swipe", "x1": 100, "y1": 800, "x2": 100, "y2": 200, "duration": 300}
     */
    private fun handleSwipe(json: JSONObject): String {
        val service = accessibilityService
            ?: return errorResponse("accessibility service not connected")

        if (!service.isActive()) {
            return errorResponse("accessibility service not active")
        }

        val x1 = json.optDouble("x1", Double.NaN).toFloat()
        val y1 = json.optDouble("y1", Double.NaN).toFloat()
        val x2 = json.optDouble("x2", Double.NaN).toFloat()
        val y2 = json.optDouble("y2", Double.NaN).toFloat()
        val duration = json.optLong("duration", 300L)

        if (x1.isNaN() || y1.isNaN() || x2.isNaN() || y2.isNaN()) {
            return errorResponse("missing or invalid coordinates")
        }

        return service.injectSwipe(x1, y1, x2, y2, duration)
    }

    /**
     * 处理截图命令
     *
     * 请求: {"action": "screencap", "quality": 80}
     * 响应: base64 编码的 JPEG 数据
     */
    private suspend fun handleScreencap(json: JSONObject): String {
        val service = screenCaptureService
            ?: return errorResponse("screen capture service not running")

        if (!service.isActive.value) {
            return errorResponse("screen capture not active")
        }

        val quality = json.optInt("quality", 80).coerceIn(1, 100)
        val jpegData = service.getLatestFrameJpeg(quality)
            ?: return errorResponse("failed to capture screen")

        val base64 = android.util.Base64.encodeToString(jpegData, android.util.Base64.NO_WRAP)
        return """{"status": "ok", "image": "$base64"}"""
    }

    /**
     * 处理心跳/查询命令
     *
     * 请求: {"action": "ping"}
     */
    private fun handlePing(): String {
        val a11yActive = accessibilityService?.isActive() == true
        val captureActive = screenCaptureService?.isActive?.value == true
        return """{"status": "ok", "a11y_active": $a11yActive, "capture_active": $captureActive}"""
    }

    /**
     * 生成错误响应
     */
    private fun errorResponse(message: String): String {
        return """{"status": "error", "message": "$message"}"""
    }
}
