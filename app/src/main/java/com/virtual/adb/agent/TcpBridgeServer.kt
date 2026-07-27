package com.virtual.adb.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.SocketException
import java.util.zip.CRC32

/**
 * ADB TCP 服务器
 *
 * 实现 ADB 协议，监听 127.0.0.1:10000，允许标准 ADB 客户端
 * 通过无障碍服务和屏幕捕捉执行有限 ADB 功能。
 *
 * 使用方式：adb connect 127.0.0.1:10000
 * 支持命令：input tap/swipe、screencap、getevent 等
 */
class TcpBridgeServer(
    private val port: Int = 10000,
    private val host: String = "127.0.0.1"
) {

    companion object {
        private const val TAG = "TcpBridgeServer"
        private const val ADB_VERSION = 0x01000000
        private const val ADB_MAX_PAYLOAD = 4096
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_LOG_ENTRIES = 100

        // ADB 协议命令（整数常量，readLeInt 以小端序读取后得到正确值）
        private const val CMD_CNXN = 0x434e584e // "CNXN"
        private const val CMD_OPEN = 0x4f50454e // "OPEN"
        private const val CMD_OKAY = 0x4f4b4159 // "OKAY"
        private const val CMD_CLSE = 0x434c5345 // "CLSE"
        private const val CMD_WRTE = 0x57525445 // "WRTE"
        private const val CMD_AUTH = 0x41555448 // "AUTH"
        private const val CMD_STLS = 0x53544c53 // "STLS"

        // AUTH 类型
        private const val AUTH_TYPE_TOKEN = 1
        private const val AUTH_TYPE_SIGNATURE = 2

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
    ) {
        val checksum: Int
            get() = dataCrc32
        val isValidMagic: Boolean
            get() = (command xor magic) == -1 // 0xFFFFFFFF
    }

    /** 流状态 */
    private class StreamState(
        val localId: Int,
        val remoteId: Int,
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

    // ─── 服务器生命周期 ──────────────────────────────────────

    fun start() {
        val existing = serverSocket
        if (existing != null && existing.isBound && !existing.isClosed) {
            Log.w(TAG, "服务器已在运行中（端口 ${existing.localPort}），跳过重复启动")
            _isRunning.value = true
            return
        }

        stopInternal()

        _startError.value = "正在启动..."
        Log.i(TAG, "尝试启动 ADB TCP 服务器，端口: $port")

        serverJob = serverScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val inetAddress = java.net.InetAddress.getByName(host)
                    val socket = ServerSocket()
                    socket.reuseAddress = true
                    socket.bind(java.net.InetSocketAddress(inetAddress, port))
                    serverSocket = socket
                    Log.i(TAG, "已绑定 $host:$port，reuseAddress=true")
                }

                val boundAddr = serverSocket!!.inetAddress?.hostAddress ?: "unknown"
                val boundPort = serverSocket!!.localPort
                _boundPort.value = boundPort
                _isRunning.value = true
                _startError.value = ""
                Log.i(TAG, "ADB TCP 服务器启动成功，监听 $boundAddr:$boundPort")

                while (isActive) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        val clientAddr = "${clientSocket.inetAddress.hostAddress}:${clientSocket.port}"
                        Log.i(TAG, "新 ADB 客户端连接: $clientAddr")
                        appendLog("→", clientAddr, "ADB 客户端已连接")

                        launch {
                            handleAdbClient(clientSocket, clientAddr)
                        }
                    } catch (e: SocketException) {
                        if (isActive) {
                            Log.e(TAG, "Accept 异常", e)
                        }
                    }
                }
            } catch (e: java.net.BindException) {
                val msg = "端口绑定失败: ${e.message}"
                Log.e(TAG, "服务器启动失败", e)
                _isRunning.value = false
                _startError.value = msg
            } catch (e: java.net.SocketException) {
                val msg = "Socket 错误: ${e.message}"
                Log.e(TAG, "服务器启动失败", e)
                _isRunning.value = false
                _startError.value = msg
            } catch (e: SecurityException) {
                val msg = "安全权限不足: ${e.message}"
                Log.e(TAG, "服务器启动失败", e)
                _isRunning.value = false
                _startError.value = msg
            } catch (e: Exception) {
                val msg = "启动失败: ${e.javaClass.simpleName} - ${e.message}"
                Log.e(TAG, "服务器启动失败", e)
                _isRunning.value = false
                _startError.value = msg
            }
        }
    }

    fun stop() {
        stopInternal()
        _startError.value = ""
        Log.i(TAG, "ADB TCP 服务器已停止")
    }

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

    // ─── ADB 客户端处理 ──────────────────────────────────────

    private suspend fun handleAdbClient(socket: java.net.Socket, clientAddr: String) {
        _clientCount.value = _clientCount.value + 1
        val streams = mutableMapOf<Int, StreamState>() // localId -> StreamState

        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            // 读取 CNXN 握手
            val cnxn = readMessage(input)
            if (cnxn == null || cnxn.command != CMD_CNXN) {
                // 显示原始字节和解析结果到 UI 日志面板
                val rawHex = lastRawHeader?.joinToString(" ") {
                    String.format("%02x", it)
                } ?: "无"
                val diag = if (cnxn != null) {
                    "cmd=0x${Integer.toHexString(cnxn.command)} " +
                        "arg0=${cnxn.arg0} arg1=${cnxn.arg1} len=${cnxn.dataLength}"
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
            Log.i(TAG, "CNXN: version=$version, maxPayload=$maxPayload, system=$systemString")
            appendLog("→", clientAddr, "CNXN v=$version payload=$maxPayload")

            // TCP 连接无需认证，直接用 OKAY + 设备身份回复
            val identity = buildDeviceIdentity()
            writeMessage(output, CMD_OKAY, 0, 0, identity.toByteArray(Charsets.UTF_8))
            appendLog("←", clientAddr, "OKAY 设备身份已发送")
            Log.i(TAG, "ADB 握手完成: $clientAddr, 设备身份: $identity")

            // 处理命令流
            while (socket.isConnected && !socket.isClosed) {
                val msg = readMessage(input) ?: break

                when (msg.command) {
                    CMD_OPEN -> {
                        val openLocalId = msg.arg0
                        val openRemoteId = msg.arg1
                        val service = msg.data?.let { raw ->
                            val str = String(raw, Charsets.UTF_8)
                            str.trimEnd('\u0000')
                        } ?: ""
                        Log.i(TAG, "OPEN: local=$openLocalId remote=$openRemoteId service=$service")
                        appendLog("→", clientAddr, "OPEN $service")

                        streams[openLocalId] = StreamState(openLocalId, openRemoteId, service)
                        writeMessage(output, CMD_OKAY, openLocalId, openRemoteId, null)
                        appendLog("←", clientAddr, "OKAY stream=$openLocalId")
                    }

                    CMD_WRTE -> {
                        val writeLocalId = msg.arg0
                        val writeRemoteId = msg.arg1
                        val data = msg.data?.let { raw ->
                            val str = String(raw, Charsets.UTF_8)
                            str.trimEnd('\u0000')
                        } ?: ""
                        Log.i(TAG, "WRTE: local=$writeLocalId remote=$writeRemoteId data=$data")
                        appendLog("→", clientAddr, "WRTE: $data.trimEnd()")

                        val stream = streams[writeLocalId]
                        if (stream != null) {
                            val response = processAdbCommand(data.trimEnd(), clientAddr)
                            appendLog("←", clientAddr, response.trimEnd())

                            // 发送 WRTE 响应数据
                            val responseData = response.toByteArray(Charsets.UTF_8)
                            writeMessage(output, CMD_WRTE, writeLocalId, writeRemoteId, responseData)
                            writeMessage(output, CMD_OKAY, writeLocalId, writeRemoteId, null)
                        } else {
                            writeMessage(output, CMD_CLSE, writeLocalId, writeRemoteId, null)
                        }
                    }

                    CMD_CLSE -> {
                        val closeLocalId = msg.arg0
                        val closeRemoteId = msg.arg1
                        streams.remove(closeLocalId)
                        Log.i(TAG, "CLSE: local=$closeLocalId")
                        appendLog("→", clientAddr, "CLSE stream=$closeLocalId")

                        writeMessage(output, CMD_CLSE, closeRemoteId, closeLocalId, null)
                    }

                    else -> {
                        Log.w(TAG, "未知命令: ${commandName(msg.command)}")
                        appendLog("✗", clientAddr, "未知命令: ${commandName(msg.command)}")
                    }
                }
            }
        } catch (e: SocketException) {
            appendLog("✗", clientAddr, "连接断开: ${e.message}")
            Log.d(TAG, "ADB 客户端断开连接: $clientAddr")
        } catch (e: Exception) {
            appendLog("✗", clientAddr, "异常: ${e.message}")
            Log.e(TAG, "处理 ADB 客户端异常", e)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
            _clientCount.value = (_clientCount.value - 1).coerceAtLeast(0)
            Log.d(TAG, "ADB 客户端处理结束，当前连接数: ${_clientCount.value}")
        }
    }

    // ─── ADB 命令处理 ──────────────────────────────────────

    private fun processAdbCommand(command: String, clientAddr: String): String {
        Log.d(TAG, "处理 ADB 命令: $command")

        return try {
            when {
                // ── input 命令 ──
                command.startsWith("input tap ") -> handleInputTap(command)
                command.startsWith("input swipe ") -> handleInputSwipe(command)
                command.startsWith("input text ") -> handleInputText(command)
                command.startsWith("input keyevent ") -> handleInputKeyevent(command)
                command == "input" || command.startsWith("input ") -> handleInputGeneric(command)

                // ── 截图 ──
                command == "screencap -p" || command == "screencap" -> handleScreencap()

                // ── 窗口管理 ──
                command == "dumpsys window displays" || command == "wm size" -> handleWmSize()
                command == "wm density" -> handleWmDensity()
                command == "wm" -> handleWmSize()

                // ── 事件 ──
                command == "getevent -lp" -> handleGetEvent()
                command == "getevent" || command.startsWith("getevent ") -> handleGetEvent()

                // ── 应用管理 ──
                command.startsWith("am ") -> handleAm(command)
                command.startsWith("pm ") -> handlePm(command)
                command.startsWith("settings ") -> handleSettings(command)

                // ── 文件系统 ──
                command == "id" -> "uid=2000(shell) gid=2000(shell) groups=2000(shell)"
                command == "whoami" -> "shell"
                command == "echo OK" -> "OK"
                command == "pwd" -> "/sdcard"
                command.startsWith("ls ") || command == "ls" -> handleLs(command)
                command.startsWith("cat ") -> handleCat(command)

                // ── 系统信息 ──
                command == "uname -a" -> "Linux virtual-adb-agent 5.15.0 ${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "aarch64"} GNU/Linux"
                command == "uname" -> "Linux"
                command == "date" -> java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", java.util.Locale.US).format(java.util.Date())
                command == "getserialno" -> android.os.Build.SERIAL ?: "unknown"

                // ── sleep ──
                command == "sleep" || command.startsWith("sleep ") -> {
                    Thread.sleep(100)
                    ""
                }

                // ── dumpsys ──
                command.startsWith("dumpsys ") -> handleDumpsys(command)

                // ── getprop ──
                command == "getprop" -> handleGetpropAll()
                command.startsWith("getprop ") -> handleGetprop(command)

                // ── 未知命令 ──
                else -> {
                    Log.w(TAG, "不支持的 ADB 命令: $command")
                    "virtual-adb-agent: '$command' not implemented"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理命令异常: $command", e)
            "virtual-adb-agent: error processing '$command': ${e.message}"
        }
    }

    private fun handleInputTap(command: String): String {
        val parts = command.split("\\s+".toRegex())
        if (parts.size < 4) return "usage: input tap <x> <y>"

        val x = parts[2].toFloatOrNull() ?: return "invalid x: ${parts[2]}"
        val y = parts[3].toFloatOrNull() ?: return "invalid y: ${parts[3]}"

        val service = accessibilityService
            ?: return "accessibility service not connected"

        if (!service.isActive()) {
            return "accessibility service not active"
        }

        return service.injectClick(x, y)
    }

    private fun handleInputSwipe(command: String): String {
        val parts = command.split("\\s+".toRegex())
        if (parts.size < 6) return "usage: input swipe <x1> <y1> <x2> <y2> [duration]"

        val x1 = parts[2].toFloatOrNull() ?: return "invalid x1: ${parts[2]}"
        val y1 = parts[3].toFloatOrNull() ?: return "invalid y1: ${parts[3]}"
        val x2 = parts[4].toFloatOrNull() ?: return "invalid x2: ${parts[4]}"
        val y2 = parts[5].toFloatOrNull() ?: return "invalid y2: ${parts[5]}"
        val duration = parts.getOrNull(6)?.toLongOrNull() ?: 300L

        val service = accessibilityService
            ?: return "accessibility service not connected"

        if (!service.isActive()) {
            return "accessibility service not active"
        }

        return service.injectSwipe(x1, y1, x2, y2, duration)
    }

    private fun handleInputText(command: String): String {
        val text = command.removePrefix("input text ")
        if (text.isEmpty()) return "usage: input text <string>"
        return "virtual-adb-agent: input text not yet implemented"
    }

    private fun handleInputKeyevent(command: String): String {
        val keyCode = command.removePrefix("input keyevent ")
        return "virtual-adb-agent: input keyevent not yet implemented"
    }

    private fun handleInputGeneric(command: String): String {
        val parts = command.split("\\s+".toRegex())
        return if (parts.size < 2) {
            "Usage: input [text|keyevent|tap|swipe] ..."
        } else {
            "virtual-adb-agent: input ${parts[1]} not yet implemented"
        }
    }

    private fun handleScreencap(): String {
        val service = screenCaptureService
            ?: return "screen capture service not running"

        if (!service.isActive.value) {
            return "screen capture not active"
        }

        val jpegData = kotlinx.coroutines.runBlocking {
            service.getLatestFrameJpeg(80)
        } ?: return "failed to capture screen"

        return android.util.Base64.encodeToString(jpegData, android.util.Base64.NO_WRAP)
    }

    private fun handleWmSize(): String {
        return "Physical size: 1080x2340"
    }

    private fun handleWmDensity(): String {
        return "Physical density: 440"
    }

    private fun handleGetEvent(): String {
        // 返回空的事件列表，表示没有物理输入设备
        return ""
    }

    private fun handleAm(command: String): String {
        return when {
            command.startsWith("am start ") -> "virtual-adb-agent: am start not implemented"
            command.startsWith("am force-stop ") -> "virtual-adb-agent: am force-stop not implemented"
            else -> "virtual-adb-agent: am command not implemented"
        }
    }

    private fun handlePm(command: String): String {
        return when {
            command == "pm list packages" -> ""
            command.startsWith("pm path ") -> ""
            else -> "virtual-adb-agent: pm command not implemented"
        }
    }

    private fun handleSettings(command: String): String {
        return "virtual-adb-agent: settings command not implemented"
    }

    private fun handleLs(command: String): String {
        return ""
    }

    private fun handleCat(command: String): String {
        val path = command.removePrefix("cat ").trim()
        return when {
            path == "/proc/version" ->
                "Linux version 5.15.0-android-${android.os.Build.VERSION.SDK_INT} " +
                "(virtual-adb-agent@localhost) (aarch64-linux-gnu-gcc) #1 SMP PREEMPT"
            path == "/proc/cpuinfo" -> buildString {
                appendLine("Processor\t: ${android.os.Build.HARDWARE}")
                appendLine("model name\t: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("Hardware\t: ${android.os.Build.BOARD}")
                appendLine("CPU implementer\t: 0x61")
                appendLine("CPU architecture: 8")
                appendLine("BogoMIPS\t: 38.40")
                appendLine("Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32")
            }
            path == "/proc/meminfo" -> buildString {
                val runtime = Runtime.getRuntime()
                val maxMem = runtime.maxMemory() / 1024
                val totalMem = runtime.totalMemory() / 1024
                val freeMem = runtime.freeMemory() / 1024
                appendLine("MemTotal:       $maxMem kB")
                appendLine("MemFree:        $freeMem kB")
                appendLine("MemAvailable:   ${maxMem - totalMem + freeMem} kB")
                appendLine("Buffers:        0 kB")
                appendLine("Cached:         0 kB")
            }
            path.startsWith("/sys/") -> "Permission denied"
            path.startsWith("/proc/") -> "Permission denied"
            path.startsWith("/sdcard/") || path.startsWith("/storage/") -> "Permission denied"
            else -> "cat: $path: No such file or directory"
        }
    }

    private fun handleDumpsys(command: String): String {
        val service = command.removePrefix("dumpsys ").trim()
        return when {
            service == "battery" || service.startsWith("battery ") -> buildString {
                appendLine("Current Battery Service state:")
                appendLine("  AC powered: false")
                appendLine("  USB powered: true")
                appendLine("  status: 2")
                appendLine("  level: 100")
                appendLine("  temperature: 250")
            }
            service.startsWith("window") -> handleWmSize()
            service.startsWith("package") -> ""
            service.startsWith("activity") -> ""
            else -> ""
        }
    }

    /**
     * 列出所有已知的系统属性（getprop 无参数）
     */
    private fun handleGetpropAll(): String {
        val props = linkedMapOf(
            "ro.build.version.sdk" to "${android.os.Build.VERSION.SDK_INT}",
            "ro.build.version.release" to android.os.Build.VERSION.RELEASE,
            "ro.build.display.id" to android.os.Build.DISPLAY,
            "ro.build.id" to android.os.Build.ID,
            "ro.build.type" to android.os.Build.TYPE,
            "ro.build.fingerprint" to android.os.Build.FINGERPRINT,
            "ro.product.model" to android.os.Build.MODEL,
            "ro.product.brand" to android.os.Build.BRAND,
            "ro.product.device" to android.os.Build.DEVICE,
            "ro.product.manufacturer" to android.os.Build.MANUFACTURER,
            "ro.product.name" to android.os.Build.PRODUCT,
            "ro.product.board" to android.os.Build.BOARD,
            "ro.product.cpu.abi" to (android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
            "ro.hardware" to android.os.Build.HARDWARE,
            "ro.board.platform" to android.os.Build.BOARD,
            "ro.build.version.codename" to android.os.Build.VERSION.CODENAME,
            "ro.build.version.security_patch" to android.os.Build.VERSION.SECURITY_PATCH,
            "ro.timezone" to java.util.TimeZone.getDefault().id,
            "persist.sys.language" to java.util.Locale.getDefault().language,
            "persist.sys.country" to java.util.Locale.getDefault().country,
            "ro.boot.serialno" to (android.os.Build.SERIAL ?: "unknown")
        )
        return props.entries.joinToString("\n") { "[${it.key}]: [${it.value}]" }
    }

    private fun handleGetprop(command: String): String {
        val prop = command.removePrefix("getprop ")
        return when (prop) {
            "ro.build.version.sdk" -> "${android.os.Build.VERSION.SDK_INT}"
            "ro.build.version.release" -> android.os.Build.VERSION.RELEASE
            "ro.build.display.id" -> android.os.Build.DISPLAY
            "ro.build.version.security_patch" -> android.os.Build.VERSION.SECURITY_PATCH
            "ro.product.model" -> android.os.Build.MODEL
            "ro.product.brand" -> android.os.Build.BRAND
            "ro.product.device" -> android.os.Build.DEVICE
            "ro.product.manufacturer" -> android.os.Build.MANUFACTURER
            "ro.product.name" -> android.os.Build.PRODUCT
            "ro.product.board" -> android.os.Build.BOARD
            "ro.product.cpu.abi" -> android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            "ro.product.cpu.abilist" -> android.os.Build.SUPPORTED_ABIS.joinToString(",")
            "ro.build.fingerprint" -> android.os.Build.FINGERPRINT
            "ro.build.id" -> android.os.Build.ID
            "ro.build.type" -> android.os.Build.TYPE
            "ro.build.version.codename" -> android.os.Build.VERSION.CODENAME
            "ro.hardware" -> android.os.Build.HARDWARE
            "ro.board.platform" -> android.os.Build.BOARD
            "ro.boot.serialno" -> android.os.Build.SERIAL
            "persist.sys.language" -> java.util.Locale.getDefault().language
            "persist.sys.country" -> java.util.Locale.getDefault().country
            "ro.timezone" -> java.util.TimeZone.getDefault().id
            "ro.build.version.preview_sdk" -> "${android.os.Build.VERSION.PREVIEW_SDK_INT}"
            "gsm.version.baseband" -> ""
            "gsm.version.radio" -> ""
            else -> ""
        }
    }

    /**
     * 构建设备身份字符串
     *
     * ADB 协议 CNXN 回复中包含设备信息，格式为 key:value 对，用分号分隔。
     * 客户端通过 adb devices -l 读取这些信息显示在设备列表中。
     */
    private fun buildDeviceIdentity(): String {
        val props = mapOf(
            "model" to android.os.Build.MODEL,
            "device" to android.os.Build.DEVICE,
            "product" to android.os.Build.PRODUCT,
            "device_model" to android.os.Build.MODEL,
            "device_manufacturer" to android.os.Build.MANUFACTURER,
            "brand" to android.os.Build.BRAND,
            "build_flavor" to android.os.Build.FINGERPRINT,
            "build_id" to android.os.Build.ID,
            "build_display_id" to android.os.Build.DISPLAY,
            "build_version" to android.os.Build.VERSION.RELEASE,
            "features" to "shell_v2"
        )
        return props.entries.joinToString(";") { "${it.key}:${it.value}" } + ";"
    }

    // ─── ADB 协议读写 ──────────────────────────────────────

    private fun readMessage(input: DataInputStream): AdbMessage? {
        return try {
            // 先读取 24 字节头的原始数据，用于调试
            val headerBytes = ByteArray(24)
            input.readFully(headerBytes)

            // 以小端序解析各字段
            val command = bytesToLeInt(headerBytes, 0)
            val arg0 = bytesToLeInt(headerBytes, 4)
            val arg1 = bytesToLeInt(headerBytes, 8)
            val dataLength = bytesToLeInt(headerBytes, 12)
            val dataCrc32 = bytesToLeInt(headerBytes, 16)
            val magic = bytesToLeInt(headerBytes, 20)

            lastRawHeader = headerBytes
            Log.d(TAG, "原始字节: ${headerBytes.joinToString(" ") {
                String.format("%02x", it)
            }}")

            val data = if (dataLength > 0 && dataLength < ADB_MAX_PAYLOAD * 2) {
                val buf = ByteArray(dataLength)
                input.readFully(buf)
                buf
            } else null

            // 校验 magic：command XOR magic 应该全为 1（0xFFFFFFFF）
            if ((command xor magic) != -1) {
                Log.w(TAG, "无效 magic: command=${commandName(command)} " +
                    "cmd=0x${Integer.toHexString(command)} magic=0x${Integer.toHexString(magic)}")
                return null
            }

            // 校验 CRC32
            if (data != null) {
                val crc = CRC32()
                crc.update(data)
                if (crc.value.toInt() != dataCrc32) {
                    Log.w(TAG, "CRC32 校验失败: expected=0x${Integer.toHexString(dataCrc32)} " +
                        "actual=0x${Integer.toHexString(crc.value.toInt())}")
                    return null
                }
            }

            AdbMessage(command, arg0, arg1, dataLength, dataCrc32, magic, data)
        } catch (e: java.io.EOFException) {
            Log.w(TAG, "读取消息失败: EOF（客户端可能已断开）")
            null
        } catch (e: SocketException) {
            Log.w(TAG, "读取消息失败: Socket 异常 - ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "读取消息异常", e)
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
        val crc32 = CRC32()
        if (data != null) {
            crc32.update(data)
        }

        // ADB 协议使用小端序，DataOutputStream 是大端序，需手动写入
        writeLeInt(output, command)
        writeLeInt(output, arg0)
        writeLeInt(output, arg1)
        writeLeInt(output, dataLen)
        writeLeInt(output, crc32.value.toInt())
        writeLeInt(output, command xor -1) // magic = command XOR 0xFFFFFFFF

        if (data != null) {
            output.write(data)
        }
        output.flush()
    }

    /**
     * 以小端序写入 4 字节整数（ADB 协议要求）
     */
    private fun writeLeInt(output: DataOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 24) and 0xFF)
    }

    /**
     * 以小端序读取 4 字节整数（从 DataInputStream）
     */
    private fun readLeInt(input: DataInputStream): Int {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        val b2 = input.readUnsignedByte()
        val b3 = input.readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    /**
     * 以小端序从字节数组中解析 4 字节整数
     */
    private fun bytesToLeInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    /**
     * 读取以空字符结尾的字符串（ADB 协议设备身份字符串）
     */
    private fun readLeString(input: DataInputStream, length: Int): String {
        val buf = ByteArray(length)
        input.readFully(buf)
        val end = buf.indexOf(0)
        return if (end >= 0) String(buf, 0, end, Charsets.UTF_8)
        else String(buf, Charsets.UTF_8)
    }

    // ─── 日志 ──────────────────────────────────────

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
