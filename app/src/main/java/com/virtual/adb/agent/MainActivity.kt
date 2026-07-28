package com.virtual.adb.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.lifecycle.lifecycleScope
import com.virtual.adb.agent.ui.MainScreen
import com.virtual.adb.agent.ui.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 主 Activity
 *
 * 响应式单页架构，Compose Material 3 动态配色。
 * 负责生命周期管理、权限引导、服务绑定。
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val viewModel: MainViewModel by viewModels()

    /** 屏幕捕捉服务绑定 */
    private var captureService: ScreenCaptureService? = null
    private var captureBound = false
    private var captureStateJob: Job? = null

    private val captureConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScreenCaptureService.LocalBinder
            captureService = binder.getService()
            captureBound = true
            VirtualAdbApp.tcpServer.screenCaptureService = captureService
            AppLogger.i(TAG, "屏幕捕捉服务已绑定")

            // 实时追踪捕捉状态，崩溃时自动更新前端
            captureStateJob?.cancel()
            captureStateJob = lifecycleScope.launch {
                captureService?.isActive?.collect { active ->
                    viewModel.updateCaptureRunning(active)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureStateJob?.cancel()
            captureStateJob = null
            captureService = null
            captureBound = false
            VirtualAdbApp.tcpServer.screenCaptureService = null
            AppLogger.w(TAG, "屏幕捕捉服务已断开")
            viewModel.updateCaptureRunning(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindCaptureService()

        setContent {
            val dynamicColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicLightColorScheme(this)
            } else {
                MaterialTheme.colorScheme
            }

            MaterialTheme(colorScheme = dynamicColor) {
                MainScreen(
                    viewModel = viewModel,
                    onOpenA11ySettings = {
                        startActivity(viewModel.openA11ySettings())
                    },
                    onRequestScreenCapture = {
                        viewModel.requestScreenCapturePermission(this@MainActivity)
                    },
                    onStopScreenCapture = {
                        viewModel.stopScreenCapture()
                    }
                )
            }
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MainViewModel.REQUEST_SCREEN_CAPTURE) {
            viewModel.handleScreenCaptureResult(resultCode, data)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkA11yStatus()
    }

    override fun onDestroy() {
        unbindCaptureService()
        super.onDestroy()
    }

    private fun bindCaptureService() {
        val intent = Intent(this, ScreenCaptureService::class.java)
        try {
            bindService(intent, captureConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            AppLogger.e(TAG, "绑定屏幕捕捉服务失败", e)
        }
    }

    private fun unbindCaptureService() {
        if (captureBound) {
            try {
                unbindService(captureConnection)
            } catch (e: Exception) {
                AppLogger.e(TAG, "解绑屏幕捕捉服务失败", e)
            }
            captureBound = false
        }
    }
}
