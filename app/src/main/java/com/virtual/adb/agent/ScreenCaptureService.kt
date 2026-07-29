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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    @Volatile private var screenWidth = 0
    @Volatile private var screenHeight = 0
    @Volatile private var screenDensity = 0
    private var displayListener: DisplayManager.DisplayListener? = null

    /** 本地 Binder，供 Activity 绑定获取服务实例 */
    inner class LocalBinder : android.os.Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        obtainScreenMetrics()
        // 及早创建通知频道，确保注册成功后再做其他操作
        createNotificationChannel()
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
                shutdownService()
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
     * 获取最新一帧的原生 Bitmap（跳过 JPEG 编解码，性能最优）
     *
     * @return Bitmap，失败时返回 null
     */
    fun getLatestFrameBitmap(): Bitmap? {
        if (!_isActive.value) return null
        return try {
            val image: Image = imageReader?.acquireLatestImage() ?: return null
            val bitmap = imageToBitmap(image)
            image.close()
            bitmap
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取 Bitmap 帧失败", e)
            null
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

        // 关键：在 Android 9+ 上，startForeground() 必须在 MediaProjection
        // 操作前调用，否则系统认为服务未处于前台，直接强杀录屏通道。
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            AppLogger.i(TAG, "前台通知已挂载")
        } catch (e: Exception) {
            AppLogger.e(TAG, "前台通知挂载失败: ${e.message}")
        }

        // 将 MediaProjection/VirtualDisplay 初始化放到协程中延迟执行，
        // 给 ColorOS/Android 9 足够时间将服务注册为前台，避免竞态条件。
        serviceScope.launch {
            delay(1000)

            try {
                val projectionManager =
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

                if (mediaProjection == null) {
                    AppLogger.e(TAG, "MediaProjection 创建失败")
                    return@launch
                }

                // 注册回调处理投影停止事件
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        AppLogger.i(TAG, "MediaProjection 被系统停止")
                        stopCapture()
                    }
                }, null)

                // 始终使用物理屏幕真实宽高创建画布
                // 重新获取一次，避免服务启动后屏幕方向已改变
                obtainScreenMetrics()
                val captureWidth = (screenWidth * CAPTURE_SCALE).toInt()
                val captureHeight = (screenHeight * CAPTURE_SCALE).toInt()
                val captureDensity = (screenDensity * CAPTURE_SCALE).toInt()

                imageReader = ImageReader.newInstance(
                    captureWidth,
                    captureHeight,
                    PixelFormat.RGBA_8888,
                    2  // 最多缓存 2 帧
                )
                AppLogger.i(TAG, "ImageReader 创建成功: ${captureWidth}x${captureHeight}")

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
                registerDisplayListener()
                AppLogger.i(TAG, "VirtualDisplay 创建成功，捕捉已启动")

            } catch (e: SecurityException) {
                AppLogger.e(TAG, "权限被系统拒绝: ${e.message}")
                stopCapture()
            } catch (e: IllegalArgumentException) {
                AppLogger.e(TAG, "ColorOS 拒绝此分辨率/Surface: ${e.message}")
                stopCapture()
            } catch (e: Exception) {
                AppLogger.e(TAG, "启动屏幕捕捉失败: ${e.message}", e)
                stopCapture()
            }
        }
    }

    /**
     * 停止捕捉（仅清理资源，保留前台服务/通知）
     *
     * MediaProjection 被系统强杀（onStop）时只清资源不删通知，
     * 避免通知一闪消失。用户主动关闭时才调用 [shutdownService]。
     */
    fun stopCapture() {
        unregisterDisplayListener()
        try {
            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            mediaProjection?.stop()
            mediaProjection = null

            _isActive.value = false
            VirtualAdbApp.tcpServer.screenCaptureService = null
            AppLogger.i(TAG, "屏幕捕捉已停止（服务/通知仍存活）")
        } catch (e: Exception) {
            AppLogger.e(TAG, "停止捕捉时出错", e)
        }
    }

    /**
     * 完全关闭服务（清理资源 + 移除前台通知 + 自杀）
     */
    fun shutdownService() {
        stopCapture()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            AppLogger.e(TAG, "停止前台服务出错", e)
        }
    }

    // ─── 屏幕旋转自适应 ────────────────────────────────────

    /**
     * 注册 DisplayListener 监听屏幕旋转，自动重建 VirtualDisplay。
     */
    private fun registerDisplayListener() {
        if (displayListener != null) return
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}

            override fun onDisplayRemoved(displayId: Int) {}

            override fun onDisplayChanged(displayId: Int) {
                recreateVirtualDisplayIfNeeded()
            }
        }
        dm.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        AppLogger.i(TAG, "DisplayListener 已注册")
    }

    private fun unregisterDisplayListener() {
        displayListener?.let {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.unregisterDisplayListener(it)
            displayListener = null
            AppLogger.i(TAG, "DisplayListener 已注销")
        }
    }

    /**
     * 检查屏幕尺寸是否变化，若是则重建 VirtualDisplay + ImageReader。
     * VirtualDisplay 创建后不能换 Surface，只能 release 旧的重建新的。
     */
    @Synchronized
    private fun recreateVirtualDisplayIfNeeded() {
        val mp = mediaProjection ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        if (metrics.widthPixels == screenWidth && metrics.heightPixels == screenHeight) return

        val oldW = screenWidth
        val oldH = screenHeight
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        AppLogger.i(TAG, "屏幕旋转: ${oldW}x${oldH} → ${screenWidth}x${screenHeight}，重建 VirtualDisplay")

        try {
            // 1. 销毁旧的
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null

            // 2. 按新尺寸重建
            val cw = (screenWidth * CAPTURE_SCALE).toInt()
            val ch = (screenHeight * CAPTURE_SCALE).toInt()
            val cd = (screenDensity * CAPTURE_SCALE).toInt()

            imageReader = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mp.createVirtualDisplay(
                "VirtualAdbAgentCapture",
                cw, ch, cd,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
            AppLogger.i(TAG, "VirtualDisplay 重建成功: ${cw}x${ch}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "重建 VirtualDisplay 失败", e)
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
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
