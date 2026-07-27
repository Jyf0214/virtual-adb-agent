package com.virtual.adb.agent

import android.app.Application
import android.util.Log

/**
 * Application 类
 *
 * 全局持有 TcpBridgeServer 单例，供各组件共享。
 */
class VirtualAdbApp : Application() {

    companion object {
        private const val TAG = "VirtualAdbApp"

        /** TCP 服务器单例 */
        val tcpServer = TcpBridgeServer()
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Virtual ADB Agent 应用启动")
    }

    override fun onTerminate() {
        tcpServer.stop()
        super.onTerminate()
        Log.i(TAG, "Virtual ADB Agent 应用终止")
    }
}
