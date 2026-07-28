package com.virtual.adb.agent

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃信息数据类（顶层类，供 CrashActivity 等外部组件引用）
 */
data class CrashInfo(
    val timestamp: String,
    val threadName: String,
    val exceptionClass: String,
    val message: String,
    val stackTrace: String,
    val deviceInfo: String
)

/**
 * Application 类
 *
 * 全局持有 TcpBridgeServer 单例，供各组件共享。
 * 安装全局未捕获异常处理器，防止应用直接崩溃到系统对话框。
 */
class VirtualAdbApp : Application() {

    companion object {
        private const val TAG = "VirtualAdbApp"

        /** TCP 服务器单例 */
        val tcpServer = TcpBridgeServer()

        /** Logcat 实时读取器 */
        val logcatReader = LogcatReader()

        /** 崩溃信息 */
        @Volatile
        var lastCrashInfo: CrashInfo? = null
            internal set
    }

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        Log.i(TAG, "Virtual ADB Agent 应用启动")
    }

    override fun onTerminate() {
        tcpServer.stop()
        super.onTerminate()
        Log.i(TAG, "Virtual ADB Agent 应用终止")
    }

    /**
     * 安装全局未捕获异常处理器
     *
     * 捕获所有未处理的异常，记录详细信息，然后跳转到崩溃页面，
     * 阻止系统弹出"应用已停止"对话框。
     */
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()

                val deviceInfo = buildString {
                    appendLine("品牌: ${Build.BRAND}")
                    appendLine("型号: ${Build.MODEL}")
                    appendLine("设备: ${Build.DEVICE}")
                    appendLine("制造商: ${Build.MANUFACTURER}")
                    appendLine("Android 版本: ${Build.VERSION.RELEASE}")
                    appendLine("SDK 版本: ${Build.VERSION.SDK_INT}")
                    appendLine(" ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
                    appendLine("指纹: ${Build.FINGERPRINT}")
                }

                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS", Locale.US
                ).format(Date())

                lastCrashInfo = CrashInfo(
                    timestamp = timestamp,
                    threadName = thread.name,
                    exceptionClass = throwable.javaClass.name,
                    message = throwable.message ?: "(无消息)",
                    stackTrace = stackTrace,
                    deviceInfo = deviceInfo
                )

                Log.e(TAG, "捕获未处理异常 [${thread.name}]: ${throwable.javaClass.simpleName}: ${throwable.message}")

                // 在主线程启动崩溃页面
                Handler(Looper.getMainLooper()).post {
                    try {
                        val intent = Intent(this@VirtualAdbApp, CrashActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "启动崩溃页面失败", e)
                    }
                }

                // 给崩溃页面一点时间启动，然后再终止进程
                Thread.sleep(2000)
            } catch (e: Exception) {
                Log.e(TAG, "崩溃处理器自身异常", e)
            }

            // 调用原始处理器（如果有），否则强制退出
            defaultHandler?.uncaughtException(thread, throwable)
                ?: Runtime.getRuntime().exit(1)
        }
    }
}
