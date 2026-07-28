package com.virtual.adb.agent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用日志工具
 *
 * 同时写入 logcat 和前端可见的系统日志控制台。
 * 解决 logcat 不可读时无法调试的问题。
 */
object AppLogger {

    private const val MAX_ENTRIES = 500

    data class LogEntry(
        val level: String,
        val tag: String,
        val message: String
    )

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("I", tag, message)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        append("D", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        append("W", tag, message)
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
        append("E", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
            append("E", tag, "$message: ${throwable.message}")
        } else {
            Log.e(tag, message)
            append("E", tag, message)
        }
    }

    private fun append(level: String, tag: String, message: String) {
        val current = _logs.value.toMutableList()
        current.add(LogEntry(level, tag, message))
        if (current.size > MAX_ENTRIES) {
            current.removeAt(0)
        }
        _logs.value = current
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
