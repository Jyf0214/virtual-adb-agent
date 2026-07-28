package com.virtual.adb.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 屏幕捕捉前台服务
 *
 * 通过 MediaProjection + ImageReader 实现后台帧捕获。
 * 使用前台服务挂载以保证服务存活。
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1001

        /** ImageReader 缩放比例（1.0 = 原始分辨率，0.5 = 半分辨率） */
        private const val CAPTURE_SCALE = 1.0f

        /** JPEG 压缩质量默认值 */
        private const val DEFAULT_JPEG_QUALITY = 100

        private const val ACTION_START = "com.virtual.adb.agent.action.START_CAPTURE"
        private const val ACTION_STOP = "com.virtual.adb.agent.action.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
    }

    /** 捕获状态 */
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    /** 本地 Binder，供 Activity 绑定获取服务实例 */
    inner class LocalBinder : android.os.Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        obtainScreenMetrics()
        AppLogger.i(TAG, "屏幕捕捉服务已创建，屏幕尺寸: ${screenWidth}x${screenHeight}, 密度: $screenDensity")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                @Suppress("DEPRECATION")
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultData != null) {
                    startCapture(resultCode, resultData)
                } else {
                    AppLogger.e(TAG, "启动捕捉失败：缺少 resultData")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
            else -> {
                AppLogger.d(TAG, "收到未知 action: ${intent?.action}")
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        serviceScope.cancel()
        super.onDestroy()
        AppLogger.i(TAG, "屏幕捕捉服务已销毁")
    }

    // ─── 公开接口 ──────────────────────────────────────────────

    /**
     * 获取最新一帧的 JPEG 数据
     *
     * @param quality JPEG 压缩质量 (1-100)
     * @return JPEG 字节数组，未捕捉或失败时返回 null
     */
    suspend fun getLatestFrameJpeg(quality: Int = DEFAULT_JPEG_QUALITY): ByteArray? {
        if (!_isActive.value) return null

        return withContext(Dispatchers.IO) {
            try {
                val image: Image = imageReader?.acquireLatestImage() ?: return@withContext null
                val bitmap = imageToBitmap(image)
                image.close()

                if (bitmap != null) {
                    val jpeg = bitmapToJpeg(bitmap, quality)
                    bitmap.recycle()
                    jpeg
                } else {
                    null
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取截图失败", e)
                null
            }
        }
    }

    /**
     * 启动捕捉
     */
    fun startCapture(resultCode: Int, resultData: Intent) {
        if (_isActive.value) {
            AppLogger.w(TAG, "捕捉已在运行中")
            return
        }

        try {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            if (mediaProjection == null) {
                AppLogger.e(TAG, "MediaProjection 创建失败")
                return
            }

            // 注册回调处理投影停止事件
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    AppLogger.i(TAG, "MediaProjection 被系统停止")
                    stopCapture()
                }
            }, null)

            // 始终使用物理屏幕真实宽高创建画布，避免 ColorOS 等系统
            // 因 VirtualDisplay 方向与物理方向不一致而强杀 MediaProjection。
            // 横屏转换由 TcpBridgeServer.handleScreencapPng 旋转逻辑完成。
            val captureWidth = (screenWidth * CAPTURE_SCALE).toInt()
            val captureHeight = (screenHeight * CAPTURE_SCALE).toInt()
            val captureDensity = (screenDensity * CAPTURE_SCALE).toInt()

            imageReader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                2  // 最多缓存 2 帧
            )

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "VirtualAdbAgentCapture",
                captureWidth,
                captureHeight,
                captureDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            _isActive.value = true
            VirtualAdbApp.tcpServer.screenCaptureService = this@ScreenCaptureService
            AppLogger.i(TAG, "屏幕捕捉已启动，横屏画布: ${captureWidth}x${captureHeight}")

            // 启动前台服务通知
            startForeground(NOTIFICATION_ID, buildNotification())

        } catch (e: Exception) {
            AppLogger.e(TAG, "启动屏幕捕捉失败", e)
            stopCapture()
        }
    }

    /**
     * 停止捕捉
     */
    fun stopCapture() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            mediaProjection?.stop()
            mediaProjection = null

            _isActive.value = false
            VirtualAdbApp.tcpServer.screenCaptureService = null
            AppLogger.i(TAG, "屏幕捕捉已停止")
        } catch (e: Exception) {
            AppLogger.e(TAG, "停止捕捉时出错", e)
        }

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            AppLogger.e(TAG, "停止前台服务出错", e)
        }
    }

    // ─── 内部实现 ──────────────────────────────────────────────

    private fun obtainScreenMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    /**
     * Image -> Bitmap 转换
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // 裁剪到实际尺寸
            if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Image 转 Bitmap 失败", e)
            null
        }
    }

    /**
     * Bitmap -> JPEG 压缩
     */
    private fun bitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), outputStream)
        return outputStream.toByteArray()
    }

    /**
     * 构建前台服务通知
     */
    private fun buildNotification(): Notification {
        createNotificationChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.foreground_service_title))
            .setContentText(getString(R.string.foreground_service_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
