package com.virtual.adb.agent

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADB TCP 服务器 (全功能伪装版，)
 *
 * 实现 ADB 协议，监听 127.0.0.1:10000，允许标准 ADB 客户端
 * 通过无障碍服务和屏幕捕捉执行有限 ADB 功能。
 *
 * 使用方式：adb connect 127.0.0.1:10000
 */
class TcpBridgeServer(
    private val port: Int = 10000,
    private val host: String = "127.0.0.1"
) {

    companion object {
        private const val TAG = "TcpBridgeServer"
        private const val ADB_VERSION = 0x01000001
        private const val ADB_MAX_PAYLOAD = 1048576
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_LOG_ENTRIES = 300

        // ADB 协议命令（小端序：bytesToLeInt 解析后的值）
        private const val CMD_CNXN = 0x4e584e43 // "CNXN"
        private const val CMD_OPEN = 0x4e45504f // "OPEN"
        private const val CMD_OKAY = 0x59414b4f // "OKAY"
        private const val CMD_CLSE = 0x45534c43 // "CLSE"
        private const val CMD_WRTE = 0x45545257 // "WRTE"
        private const val CMD_AUTH = 0x48545541 // "AUTH"
        private const val CMD_STLS = 0x534c5453 // "STLS"
    }

    /** 日志条目 */
    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val direction: String,
        val client: String,
        val content: String
    )

    /** ADB 消息 */
    data class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val dataCrc32: Int,
        val magic: Int,
        val data: ByteArray? = null
    )

    /** 流状态 */
    private class StreamState(
        val serverStreamId: Int,
        val clientStreamId: Int,
        val service: String
    )

    /** 服务运行状态 */
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** 当前连接的客户端数量 */
    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount.asStateFlow()

    /** TCP 服务器绑定的端口 */
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

    /** 无障碍服务引用 */
    var accessibilityService: AccessibilityBridgeService? = null

    /** 屏幕捕捉服务引用 */
    var screenCaptureService: ScreenCaptureService? = null

    /** 上一次 readMessage 读到的原始 24 字节头（调试用） */
    @Volatile
    private var lastRawHeader: ByteArray? = null

    /** CNXN 协商后的协议版本号 */
    @Volatile
    private var negotiatedVersion: Int = 0

    /** 服务端自增流 ID 生成器 */
    private val streamIdGenerator = AtomicInteger(1)

    @Suppress("DEPRECATION")
    private fun getDeviceSerial(): String = android.os.Build.SERIAL ?: "emulator-5554"

    // ─── 服务器生命周期 ──────────────────────────────────────

    fun start(host: String = "127.0.0.1") {
        val existing = serverSocket
        if (existing != null && existing.isBound && !existing.isClosed) {
            AppLogger.w(TAG, "服务器已在运行中（端口 ${existing.localPort}），跳过重复启动")
            _isRunning.value = true
            return
        }

        stopInternal()

        _startError.value = "正在启动..."
        AppLogger.i(TAG, "尝试启动 ADB TCP 服务器，端口: $port")

        serverJob = serverScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val inetAddress = java.net.InetAddress.getByName(host)
                    val socket = ServerSocket()
                    socket.reuseAddress = true
                    socket.bind(java.net.InetSocketAddress(inetAddress, port))
                    serverSocket = socket
                    AppLogger.i(TAG, "已绑定 $host:$port，reuseAddress=true")
                }

                val boundAddr = serverSocket!!.inetAddress?.hostAddress ?: "unknown"
                val boundPort = serverSocket!!.localPort
                _boundPort.value = boundPort
                _isRunning.value = true
                _startError.value = ""
                AppLogger.i(TAG, "ADB TCP 服务器启动成功，监听 $boundAddr:$boundPort")

                while (isActive) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        val clientAddr = "${clientSocket.inetAddress.hostAddress}:${clientSocket.port}"
                        AppLogger.i(TAG, "新 ADB 客户端连接: $clientAddr")
                        appendLog("→", clientAddr, "ADB 客户端已连接")

                        launch {
                            handleAdbClient(clientSocket, clientAddr)
                        }
                    } catch (e: SocketException) {
                        if (isActive) {
                            AppLogger.e(TAG, "Accept 异常", e)
                        }
                    }
                }
            } catch (e: Exception) {
                val msg = "启动失败: ${e.javaClass.simpleName} - ${e.message}"
                AppLogger.e(TAG, "服务器启动失败", e)
                _isRunning.value = false
                _startError.value = msg
            }
        }
    }

    fun stop() {
        stopInternal()
        _startError.value = ""
        AppLogger.i(TAG, "ADB TCP 服务器已停止")
    }

    private fun stopInternal() {
        _isRunning.value = false
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            AppLogger.e(TAG, "关闭 ServerSocket 异常", e)
        }
        serverSocket = null
        _clientCount.value = 0
    }

    // ─── ADB 客户端处理 ──────────────────────────────────────

    private suspend fun handleAdbClient(socket: java.net.Socket, clientAddr: String) {
        _clientCount.value = _clientCount.value + 1
        val streams = mutableMapOf<Int, StreamState>() // serverStreamId -> StreamState

        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            // 读取 CNXN 握手
            val cnxn = readMessage(input)
            if (cnxn == null || cnxn.command != CMD_CNXN) {
                val rawHex = lastRawHeader?.joinToString(" ") { String.format("%02x", it) } ?: "无"
                val diag = if (cnxn != null) {
                    "cmd=0x${Integer.toHexString(cnxn.command)} arg0=${cnxn.arg0} arg1=${cnxn.arg1} len=${cnxn.dataLength}"
                } else {
                    "readMessage 返回 null"
                }
                appendLog("✗", clientAddr, "握手失败: $diag")
                appendLog("✗", clientAddr, "原始24字节: $rawHex")
                socket.close()
                return
            }

            val version = cnxn.arg0
            val maxPayload = cnxn.arg1
            val systemString = cnxn.data?.toString(Charsets.UTF_8)?.trimEnd('\u0000') ?: ""
            AppLogger.i(TAG, "CNXN: version=$version, maxPayload=$maxPayload, system=$systemString")
            appendLog("→", clientAddr, "CNXN v=$version payload=$maxPayload")

            negotiatedVersion = version

            // 回复 CNXN + 设备身份完成握手
            val identity = buildDeviceIdentity()
            writeMessage(output, CMD_CNXN, ADB_VERSION, ADB_MAX_PAYLOAD, identity.toByteArray(Charsets.UTF_8))
            appendLog("←", clientAddr, "CNXN v=$ADB_VERSION payload=$ADB_MAX_PAYLOAD")
            AppLogger.i(TAG, "ADB 握手完成: $clientAddr")

            // 处理命令流
            while (socket.isConnected && !socket.isClosed) {
                val msg = readMessage(input) ?: break
                val cmdName = commandName(msg.command)
                val dataPreview = formatDataPreview(msg.data)

                appendLog("→", clientAddr, "$cmdName arg0=${msg.arg0} arg1=${msg.arg1} len=${msg.dataLength}$dataPreview")

                when (msg.command) {
                    CMD_OPEN -> {
                        val clientStreamId = msg.arg0 // 客户端分配的 ID
                        val serverStreamId = streamIdGenerator.getAndIncrement() // 服务端分配的唯一 ID

                        val service = msg.data?.let { raw ->
                            val end = raw.indexOf(0)
                            if (end >= 0) String(raw, 0, end, Charsets.UTF_8)
                            else String(raw, Charsets.UTF_8)
                        } ?: ""

                        streams[serverStreamId] = StreamState(serverStreamId, clientStreamId, service)

                        // 1. 回复 OKAY (arg0 = serverStreamId, arg1 = clientStreamId)
                        writeMessage(output, CMD_OKAY, serverStreamId, clientStreamId, null)
                        appendLog("←", clientAddr, "OKAY stream=$clientStreamId (server=$serverStreamId) → $service")

                        // sync: 是持久二进制通道，保持流开启，等待后续 WRTE 指令
                        if (service == "sync:") {
                            appendLog("→", clientAddr, "开启 Sync 持久流通道")
                        } else {
                            // 普通 Shell / Exec 命令处理
                            val cleanCmd = service
                                .removePrefix("shell:")
                                .removePrefix("exec:")
                                .removePrefix("exec-out:")
                                .trim()

                            appendLog("→", clientAddr, "请求命令: $cleanCmd")
                            val responseData = processCommandToBytes(cleanCmd, clientAddr)

                            // 【核心修复】：CMD_OPEN 中加入高效非阻塞 256KB 分块发送
                            if (responseData.isNotEmpty()) {
                                val chunkSize = 786432 // 768 KB
                                var offset = 0
                                val totalLen = responseData.size

                                while (offset < totalLen) {
                                    val len = minOf(chunkSize, totalLen - offset)
                                    val chunk = ByteArray(len)
                                    System.arraycopy(responseData, offset, chunk, 0, len)
                                    writeMessage(output, CMD_WRTE, serverStreamId, clientStreamId, chunk)
                                    offset += len
                                }
                            }

                            // 命令处理完毕，发送 CLSE
                            writeMessage(output, CMD_CLSE, serverStreamId, clientStreamId, null)
                            appendLog("←", clientAddr, "CLSE stream=$clientStreamId")
                            streams.remove(serverStreamId)
                        }
                    }

                    CMD_WRTE -> {
                        val clientStreamId = msg.arg0
                        val serverStreamId = msg.arg1
                        val stream = streams[serverStreamId]

                        // 回复 OKAY 确认收到 WRTE
                        writeMessage(output, CMD_OKAY, serverStreamId, clientStreamId, null)

                        // sync: 服务的二进制指令处理
                        if (stream?.service == "sync:") {
                            val syncData = msg.data
                            if (syncData != null && syncData.size >= 4) {
                                val syncCmd = String(syncData, 0, 4, Charsets.US_ASCII)
                                appendLog("→", clientAddr, "Sync 指令: $syncCmd")

                                when (syncCmd) {
                                    "STAT", "LSTA" -> {
                                        // 回复 16 字节 STAT 结构体：mode=0 表示文件不存在
                                        val statBuf = java.nio.ByteBuffer.allocate(16).apply {
                                            order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                            put("STAT".toByteArray(Charsets.US_ASCII))
                                            putInt(0) // mode = 0 (File Not Found)
                                            putInt(0) // size = 0
                                            putInt(0) // time = 0
                                        }
                                        writeMessage(output, CMD_WRTE, serverStreamId, clientStreamId, statBuf.array())
                                    }
                                    "SEND" -> {
                                        // 回复 FAIL 强制客户端降级为 input tap/swipe
                                        val failMsg = "Permission denied"
                                        val failBytes = failMsg.toByteArray(Charsets.US_ASCII)
                                        val failBuf = java.nio.ByteBuffer.allocate(8 + failBytes.size).apply {
                                            order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                            put("FAIL".toByteArray(Charsets.US_ASCII))
                                            putInt(failBytes.size)
                                            put(failBytes)
                                        }
                                        writeMessage(output, CMD_WRTE, serverStreamId, clientStreamId, failBuf.array())
                                    }
                                    "QUIT" -> {
                                        writeMessage(output, CMD_CLSE, serverStreamId, clientStreamId, null)
                                        streams.remove(serverStreamId)
                                    }
                                    else -> {
                                        // 其他未知 Sync 指令，回复 FAIL
                                        val failMsg = "Operation not permitted"
                                        val failBytes = failMsg.toByteArray(Charsets.US_ASCII)
                                        val failBuf = java.nio.ByteBuffer.allocate(8 + failBytes.size).apply {
                                            order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                            put("FAIL".toByteArray(Charsets.US_ASCII))
                                            putInt(failBytes.size)
                                            put(failBytes)
                                        }
                                        writeMessage(output, CMD_WRTE, serverStreamId, clientStreamId, failBuf.array())
                                    }
                                }
                            }
                        } else {
                            // 普通 Shell WRTE 逻辑
                            val command = msg.data?.let { raw ->
                                val end = raw.indexOf(0)
                                if (end >= 0) String(raw, 0, end, Charsets.UTF_8)
                                else String(raw, Charsets.UTF_8)
                            }?.trim() ?: ""

                            if (command.isNotEmpty()) {
                                appendLog("→", clientAddr, "请求命令: $command")
                                val responseData = processCommandToBytes(command, clientAddr)

                                if (responseData.isNotEmpty()) {
                                    // 分块传输：超过 256KB 时拆分发送，避免 Broken pipe
                                    val chunkSize = 786432 // 768 KB
                                    var offset = 0
                                    val totalLen = responseData.size

                                    while (offset < totalLen) {
                                        val len = minOf(chunkSize, totalLen - offset)
                                        val chunk = ByteArray(len)
                                        System.arraycopy(responseData, offset, chunk, 0, len)
                                        writeMessage(output, CMD_WRTE, serverStreamId, clientStreamId, chunk)
                                        offset += len
                                    }
                                }
                                writeMessage(output, CMD_CLSE, serverStreamId, clientStreamId, null)
                                appendLog("←", clientAddr, "CLSE stream=$clientStreamId")
                                streams.remove(serverStreamId)
                            }
                        }
                    }

                    CMD_CLSE -> {
                        val clientStreamId = msg.arg0
                        val serverStreamId = msg.arg1
                        streams.remove(serverStreamId)
                        writeMessage(output, CMD_CLSE, serverStreamId, clientStreamId, null)
                    }

                    CMD_OKAY -> {
                        appendLog("→", clientAddr, "OKAY (ack)")
                    }

                    else -> {
                        appendLog("✗", clientAddr, "未处理命令，已忽略")
                    }
                }
            }
        } catch (e: SocketException) {
            appendLog("✗", clientAddr, "连接断开: ${e.message}")
        } catch (e: Exception) {
            appendLog("✗", clientAddr, "异常: ${e.message}")
            AppLogger.e(TAG, "处理 ADB 客户端异常", e)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
            _clientCount.value = (_clientCount.value - 1).coerceAtLeast(0)
        }
    }

    // ─── ADB 命令全量路由处理 ──────────────────────────────────────

    private suspend fun processCommandToBytes(command: String, clientAddr: String): ByteArray {
        AppLogger.d(TAG, "执行命令: $command")

        return try {
            val cmd = command
                .removePrefix("shell:")
                .removePrefix("exec:")
                .removePrefix("exec-out:")
                .trim()

            when {
                // 检测 screencap + 管道，强制降级为普通 screencap -p
                cmd.contains("screencap") && (cmd.contains("nc ") || cmd.contains("gzip")) -> {
                    appendLog("→", clientAddr, "管道探测已拦截，强制降级")
                    "\n".toByteArray(Charsets.UTF_8)
                }

                // 统一全量包含 screencap 的指令返回 PNG
                cmd.contains("screencap") -> handleScreencapPng(clientAddr)

                // 设备 UUID / Android ID
                cmd.contains("android_id") || cmd.contains("serialno") || cmd.contains("boot_id") -> handleAndroidId()

                // 屏幕分辨率与参数
                cmd.contains("wm size") -> handleWmSize(cmd)
                cmd.contains("wm density") -> handleWmDensity()
                cmd.contains("dumpsys window") -> handleDumpsysWindow()

                // 触控与按键 (无障碍处理)
                cmd.startsWith("input tap ") -> handleInputTap(cmd)
                cmd.startsWith("input swipe ") -> handleInputSwipe(cmd)
                cmd.startsWith("input keyevent ") -> handleInputKeyevent(cmd)
                cmd.startsWith("input text ") -> handleInputText(cmd)
                cmd == "input" || cmd.startsWith("input ") -> "\n".toByteArray(Charsets.UTF_8)

                // 系统属性 getprop
                cmd == "getprop" -> handleGetpropAll().toByteArray(Charsets.UTF_8)
                cmd.startsWith("getprop ") -> handleGetprop(cmd).toByteArray(Charsets.UTF_8)

                // 应用生命周期
                cmd.startsWith("am start") || cmd.startsWith("monkey") -> handleAmStart(cmd)
                cmd.startsWith("am force-stop") -> "\n".toByteArray(Charsets.UTF_8)
                cmd.startsWith("pidof") || cmd.startsWith("ps") -> "12345\n".toByteArray(Charsets.UTF_8)

                // 常用 Linux 辅助命令
                cmd == "id" -> "uid=2000(shell) gid=2000(shell) groups=2000(shell)\n".toByteArray(Charsets.UTF_8)
                cmd == "whoami" -> "shell\n".toByteArray(Charsets.UTF_8)
                cmd == "echo OK" -> "OK\n".toByteArray(Charsets.UTF_8)
                cmd == "pwd" -> "/sdcard\n".toByteArray(Charsets.UTF_8)
                cmd == "getevent -lp" || cmd.startsWith("getevent") -> "\n".toByteArray(Charsets.UTF_8)
                cmd.startsWith("cat ") -> handleCat(cmd).toByteArray(Charsets.UTF_8)

                // 核心安全兜底
                else -> "\n".toByteArray(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "处理命令异常: $command", e)
            "\n".toByteArray(Charsets.UTF_8)
        }
    }

    // ─── 具体命令实现 ──────────────────────────────────────

    private fun handleAndroidId(): ByteArray {
        val service = accessibilityService
        val resolver = service?.contentResolver
        val androidId = if (resolver != null) {
            Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID)
        } else null

        val resultId = androidId ?: "a1b2c3d4e5f6a7b8"
        return "$resultId\n".toByteArray(Charsets.UTF_8)
    }

    private fun handleScreencapPng(clientAddr: String): ByteArray {
        val service = screenCaptureService
            ?: return "screencap: screen capture service not running\n".toByteArray(Charsets.UTF_8)

        if (!service.isActive.value) {
            return "screencap: screen capture not active\n".toByteArray(Charsets.UTF_8)
        }

        return runBlocking {
            val startTime = if (ServerConfig.enableVerboseLog.value) System.currentTimeMillis() else 0

            val jpegData = service.getLatestFrameJpeg(ServerConfig.jpegQuality.value)
            if (jpegData != null) {
                // 调试存图：立即保存原始 JPEG 数据（无论后续处理是否成功）
                if (ServerConfig.enableDebugSave.value) {
                    val debugDir = service.getExternalFilesDir(null) ?: service.filesDir
                    val savedPath = DebugScreenshotManager.saveScreenshot(debugDir, jpegData)
                    if (savedPath != null) {
                        if (ServerConfig.enableVerboseLog.value) {
                            appendLog("ℹ", clientAddr, "原始 JPEG 已保存: $savedPath")
                        }
                    }
                }

                var bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                if (bitmap == null) {
                    appendLog("✗", clientAddr, "解码失败: JPEG 数据损坏无法解析 (${jpegData.size} bytes)")
                    return@runBlocking "screencap: failed to decode internal image buffer\n".toByteArray(Charsets.UTF_8)
                }

                // 第一步：针对竖向 Buffer (如 1080x1920) 转换为标准横屏 (1920x1080)
                if (bitmap.width < bitmap.height) {
                    val matrix = android.graphics.Matrix()
                    matrix.postRotate(270f)
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (ServerConfig.enableVerboseLog.value) {
                        appendLog("ℹ", clientAddr, "竖屏转横屏: 旋转 270°")
                    }
                }

                // 第二步：智能缩放
                if (ServerConfig.enableSmartScale.value) {
                    val targetWidth = ServerConfig.smartScaleTargetWidth.value
                    if (bitmap.width > targetWidth) {
                        val scale = targetWidth.toFloat() / bitmap.width
                        val targetHeight = (bitmap.height * scale).toInt()
                        bitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                    }
                }

                if (ServerConfig.enableVerboseLog.value) {
                    appendLog("ℹ", clientAddr, "最终截图尺寸: ${bitmap.width} x ${bitmap.height}")
                }

                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                val result = baos.toByteArray()

                // 调试存图（保留 10 张，带时间戳命名）
                if (ServerConfig.enableDebugSave.value) {
                    val debugDir = service.getExternalFilesDir(null) ?: service.filesDir
                    val savedPath = DebugScreenshotManager.saveScreenshot(debugDir, result)
                    if (savedPath != null) {
                        if (ServerConfig.enableVerboseLog.value) {
                            appendLog("ℹ", clientAddr, "处理后 PNG 已保存: $savedPath")
                        }
                    } else {
                        appendLog("✗", clientAddr, "保存处理后 PNG 失败")
                    }
                }

                if (ServerConfig.enableVerboseLog.value) {
                    val elapsed = System.currentTimeMillis() - startTime
                    appendLog("ℹ", clientAddr, "截图完成: ${result.size} bytes, 耗时 ${elapsed}ms")
                }

                result
            } else {
                "screencap: failed to capture frame\n".toByteArray(Charsets.UTF_8)
            }
        }
    }

    private fun handleWmSize(cmd: String): ByteArray {
        val metrics = android.content.res.Resources.getSystem().displayMetrics
        val rawW = metrics.widthPixels
        val rawH = metrics.heightPixels

        // 根据 UI 选中的模式动态计算分辨率
        val (finalW, finalH) = when (ServerConfig.resolutionMode.value) {
            ResolutionMode.REAL_SYSTEM -> Pair(rawW, rawH)
            ResolutionMode.FORCE_LANDSCAPE -> Pair(kotlin.math.max(rawW, rawH), kotlin.math.min(rawW, rawH))
            ResolutionMode.FORCE_PORTRAIT -> Pair(kotlin.math.min(rawW, rawH), kotlin.math.max(rawW, rawH))
            ResolutionMode.CUSTOM -> Pair(ServerConfig.customWidth.value, ServerConfig.customHeight.value)
        }

        return if (cmd.contains("grep") || cmd.contains("tail")) {
            "$finalW\n$finalH\n".toByteArray(Charsets.UTF_8)
        } else {
            "Physical size: ${finalW}x${finalH}\n".toByteArray(Charsets.UTF_8)
        }
    }

    private fun handleWmDensity(): ByteArray {
        val metrics = android.content.res.Resources.getSystem().displayMetrics
        return "Physical density: ${metrics.densityDpi}\n".toByteArray(Charsets.UTF_8)
    }

    private fun handleDumpsysWindow(): ByteArray {
        val metrics = android.content.res.Resources.getSystem().displayMetrics
        val result = "  init=${metrics.widthPixels}x${metrics.heightPixels} ${metrics.densityDpi}dpi cur=${metrics.widthPixels}x${metrics.heightPixels}\n"
        return result.toByteArray(Charsets.UTF_8)
    }

    private suspend fun handleInputTap(command: String): ByteArray {
        val parts = command.split("\\s+".toRegex())
        if (parts.size >= 4) {
            val x = parts[2].toFloatOrNull()
            val y = parts[3].toFloatOrNull()
            if (x != null && y != null) {
                accessibilityService?.injectClick(x, y)
                delay(100L)
            }
        }
        return "\n".toByteArray(Charsets.UTF_8)
    }

    private suspend fun handleInputSwipe(command: String): ByteArray {
        val parts = command.split("\\s+".toRegex())
        if (parts.size >= 6) {
            val x1 = parts[2].toFloatOrNull()
            val y1 = parts[3].toFloatOrNull()
            val x2 = parts[4].toFloatOrNull()
            val y2 = parts[5].toFloatOrNull()
            val duration = parts.getOrNull(6)?.toLongOrNull() ?: 300L
            if (x1 != null && y1 != null && x2 != null && y2 != null) {
                accessibilityService?.injectSwipe(x1, y1, x2, y2, duration)
                // 阻塞到滑动真正完成，防止下一条指令中断当前手势
                delay(duration + 50L)
            }
        }
        return "\n".toByteArray(Charsets.UTF_8)
    }

    private suspend fun handleInputKeyevent(command: String): ByteArray {
        val parts = command.split("\\s+".toRegex())
        if (parts.size >= 3) {
            when (parts[2]) {
                "3", "KEYCODE_HOME" -> accessibilityService?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                "4", "KEYCODE_BACK" -> accessibilityService?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                "187", "KEYCODE_APP_SWITCH" -> accessibilityService?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            }
            delay(150L)
        }
        return "\n".toByteArray(Charsets.UTF_8)
    }

    private suspend fun handleInputText(command: String): ByteArray {
        delay(100L)
        return "\n".toByteArray(Charsets.UTF_8)
    }

    private fun handleAmStart(command: String): ByteArray {
        try {
            val regex = Regex("([a-zA-Z0-9_]+\\.[a-zA-Z0-9_.]+)")
            val pkg = regex.find(command)?.value
            val service = accessibilityService
            if (pkg != null && service != null) {
                val intent = service.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    service.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "启动应用失败: $command", e)
        }
        return "Starting: Intent { ... }\n".toByteArray(Charsets.UTF_8)
    }

    private fun handleCat(command: String): String {
        val path = command.removePrefix("cat ").trim()
        return when {
            path == "/proc/sys/kernel/random/boot_id" -> "a1b2c3d4-e5f6-7890-abcd-ef0123456789\n"
            path == "/proc/version" -> "Linux version 5.15.0-android (virtual-adb)\n"
            else -> "\n"
        }
    }

    private fun handleGetpropAll(): String {
        val props = linkedMapOf(
            "ro.build.version.sdk" to "${android.os.Build.VERSION.SDK_INT}",
            "ro.build.version.release" to android.os.Build.VERSION.RELEASE,
            "ro.product.model" to android.os.Build.MODEL,
            "ro.product.brand" to android.os.Build.BRAND,
            "ro.product.device" to android.os.Build.DEVICE,
            "ro.product.manufacturer" to android.os.Build.MANUFACTURER,
            "ro.product.name" to android.os.Build.PRODUCT,
            "ro.product.cpu.abi" to (android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"),
            "ro.boot.serialno" to getDeviceSerial(),
            "ro.sf.lcd_density" to "${android.content.res.Resources.getSystem().displayMetrics.densityDpi}"
        )
        return props.entries.joinToString("\n") { "[${it.key}]: [${it.value}]" } + "\n"
    }

    private fun handleGetprop(command: String): String {
        val prop = command.removePrefix("getprop ").trim()
        return when (prop) {
            "ro.build.version.sdk" -> "${android.os.Build.VERSION.SDK_INT}\n"
            "ro.build.version.release" -> "${android.os.Build.VERSION.RELEASE}\n"
            "ro.product.model" -> "${android.os.Build.MODEL}\n"
            "ro.product.brand" -> "${android.os.Build.BRAND}\n"
            "ro.product.device" -> "${android.os.Build.DEVICE}\n"
            "ro.product.manufacturer" -> "${android.os.Build.MANUFACTURER}\n"
            "ro.product.name" -> "${android.os.Build.PRODUCT}\n"
            "ro.product.cpu.abi" -> "${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"}\n"
            "ro.boot.serialno", "ro.serialno" -> "${getDeviceSerial()}\n"
            "ro.sf.lcd_density" -> "${android.content.res.Resources.getSystem().displayMetrics.densityDpi}\n"
            else -> "\n"
        }
    }

    private fun buildDeviceIdentity(): String {
        val serial = getDeviceSerial()
        val props = linkedMapOf(
            "serialno" to serial,
            "model" to android.os.Build.MODEL,
            "device" to android.os.Build.DEVICE,
            "product" to android.os.Build.PRODUCT,
            "features" to "shell_v2"
        )
        return props.entries.joinToString(";") { "${it.key}:${it.value}" } + ";"
    }

    // ─── ADB 协议读写 ──────────────────────────────────────

    private fun readMessage(input: DataInputStream): AdbMessage? {
        return try {
            val headerBytes = ByteArray(24)
            input.readFully(headerBytes)

            val command = bytesToLeInt(headerBytes, 0)
            val arg0 = bytesToLeInt(headerBytes, 4)
            val arg1 = bytesToLeInt(headerBytes, 8)
            val dataLength = bytesToLeInt(headerBytes, 12)
            val dataCrc32 = bytesToLeInt(headerBytes, 16)
            val magic = bytesToLeInt(headerBytes, 20)

            lastRawHeader = headerBytes

            val data = if (dataLength > 0 && dataLength < ADB_MAX_PAYLOAD * 2) {
                val buf = ByteArray(dataLength)
                input.readFully(buf)
                buf
            } else null

            // 校验 magic
            if ((command xor magic) != -1) {
                AppLogger.w(TAG, "无效 magic")
                return null
            }

            // 跳过高版本协议或无 checksum 的校验
            val skipChecksum = if (command == CMD_CNXN) {
                arg0 >= 0x01000001
            } else {
                negotiatedVersion >= 0x01000001 || dataCrc32 == 0
            }

            if (data != null && !skipChecksum) {
                var calcSum = 0
                for (b in data) {
                    calcSum += (b.toInt() and 0xFF)
                }
                if (calcSum != dataCrc32) {
                    AppLogger.w(TAG, "校验和失败: expected=$dataCrc32 actual=$calcSum")
                    return null
                }
            }

            AdbMessage(command, arg0, arg1, dataLength, dataCrc32, magic, data)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMessage(
        output: DataOutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        data: ByteArray?
    ) {
        val dataLen = data?.size ?: 0
        var byteSum = 0
        if (data != null) {
            for (b in data) {
                byteSum += (b.toInt() and 0xFF)
            }
        }

        writeLeInt(output, command)
        writeLeInt(output, arg0)
        writeLeInt(output, arg1)
        writeLeInt(output, dataLen)
        writeLeInt(output, byteSum)
        writeLeInt(output, command xor -1)

        if (data != null) {
            output.write(data)
        }
        output.flush()
    }

    private fun bytesToLeInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeLeInt(output: DataOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 24) and 0xFF)
    }

    private fun appendLog(direction: String, client: String, content: String) {
        val entry = LogEntry(direction = direction, client = client, content = content)
        val current = _logs.value.toMutableList()
        current.add(entry)
        if (current.size > MAX_LOG_ENTRIES) {
            current.removeAt(0)
        }
        _logs.value = current
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun formatDataPreview(data: ByteArray?): String {
        if (data == null || data.isEmpty()) return ""
        val allPrintable = data.all { b ->
            val c = b.toInt() and 0xFF
            c in 0x20..0x7E || c == 0x0A || c == 0x0D || c == 0x09
        }
        return if (allPrintable) {
            val text = String(data, Charsets.UTF_8).trimEnd('\u0000')
            if (text.length > 300) " data=\"${text.take(300)}...\""
            else " data=\"$text\""
        } else {
            val hex = data.take(64).joinToString(" ") { String.format("%02x", it) }
            val suffix = if (data.size > 64) " ...(${data.size}B)" else " (${data.size}B)"
            " hex=$hex$suffix"
        }
    }

    private fun commandName(cmd: Int?): String {
        return when (cmd) {
            CMD_CNXN -> "CNXN"
            CMD_OPEN -> "OPEN"
            CMD_OKAY -> "OKAY"
            CMD_CLSE -> "CLSE"
            CMD_WRTE -> "WRTE"
            CMD_AUTH -> "AUTH"
            CMD_STLS -> "STLS"
            else -> "UNKNOWN(0x${Integer.toHexString(cmd ?: 0)})"
        }
    }
}
