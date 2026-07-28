package com.virtual.adb.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 实时读取 Android logcat 原生日志
 *
 * 启动一个 logcat 进程，持续读取输出并推送到 StateFlow，
 * 供 UI 控制台实时显示。
 */
class LogcatReader {

    companion object {
        private const val MAX_LOG_LINES = 500
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var process: Process? = null
    private var readerJob: Job? = null

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun start() {
        if (_isRunning.value) return

        try {
            // 清除旧日志，只显示新产生的
            ProcessBuilder("logcat", "-c").start().waitFor()

            val proc = ProcessBuilder("logcat", "-v", "threadtime")
                .redirectErrorStream(true)
                .start()
            process = proc

            _isRunning.value = true
            _logs.value = emptyList()

            readerJob = scope.launch {
                try {
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    var line: String?
                    while (isActive) {
                        line = reader.readLine() ?: break
                        if (line.isNotEmpty()) {
                            val current = _logs.value.toMutableList()
                            current.add(line)
                            if (current.size > MAX_LOG_LINES) {
                                current.removeAt(0)
                            }
                            _logs.value = current
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    _isRunning.value = false
                }
            }
        } catch (_: Exception) {
            _isRunning.value = false
        }
    }

    fun stop() {
        readerJob?.cancel()
        readerJob = null
        try {
            process?.destroy()
        } catch (_: Exception) {}
        process = null
        _isRunning.value = false
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun destroy() {
        stop()
        scope.cancel()
    }
}
