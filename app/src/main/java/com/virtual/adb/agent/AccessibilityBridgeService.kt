package com.virtual.adb.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.random.Random

/**
 * 无障碍桥梁服务
 */
class AccessibilityBridgeService : AccessibilityService() {

    companion object {
        private const val TAG = "A11yBridgeService"

        /** 静态实例引用 */
        @Volatile
        var instance: AccessibilityBridgeService? = null
            private set

        /** 坐标偏移标准差（像素） */
        private const val GAUSSIAN_OFFSET_STD = 3.0

        /** 附加线段长度范围（像素） */
        private const val SQUEEZE_MIN = 1.0
        private const val SQUEEZE_MAX = 2.0

        /** 按压时间范围（毫秒） */
        private const val PRESS_DURATION_MEAN_MS = 85L
        private const val PRESS_DURATION_STD_MS = 15L
        private const val PRESS_DURATION_MIN_MS = 60L
        private const val PRESS_DURATION_MAX_MS = 110L

        /** 滑动时的分段步数 */
        private const val SWIPE_STEPS = 20
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var screenWidth = 0
    private var screenHeight = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        val metrics: DisplayMetrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        instance = this
        VirtualAdbApp.tcpServer.accessibilityService = this
        AppLogger.i(TAG, "无障碍服务已连接，屏幕尺寸: ${screenWidth}x${screenHeight}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        AppLogger.w(TAG, "无障碍服务被系统中断")
    }

    override fun onDestroy() {
        instance = null
        VirtualAdbApp.tcpServer.accessibilityService = null
        serviceScope.cancel()
        super.onDestroy()
        AppLogger.i(TAG, "无障碍服务已销毁")
    }

    /**
     * @param x 目标 X 坐标
     * @param y 目标 Y 坐标
     * @return JSON 响应字符串
     */
    fun injectClick(x: Float, y: Float): String {
        val jitteredX = x + randomGaussianOffset()
        val jitteredY = y + randomGaussianOffset()
        val pressDuration = randomPressDuration()

        // 从中心产生微小偏移线段
        val squeezeAngle = Random.nextFloat() * 2f * Math.PI.toFloat()
        val squeezeLen = Random.nextFloat() * (SQUEEZE_MAX.toFloat() - SQUEEZE_MIN.toFloat()) + SQUEEZE_MIN.toFloat()
        val dx = kotlin.math.cos(squeezeAngle.toDouble()).toFloat() * squeezeLen
        val dy = kotlin.math.sin(squeezeAngle.toDouble()).toFloat() * squeezeLen

        val path = Path().apply {
            moveTo(jitteredX, jitteredY)
            // 附加微小线段
            lineTo(jitteredX + dx * 0.5f, jitteredY + dy * 0.5f)
            lineTo(jitteredX - dx * 0.5f, jitteredY - dy * 0.5f)
            lineTo(jitteredX, jitteredY)
        }

        val gesture = buildGestureDescription(path, pressDuration)
        return dispatchAndAwait(gesture, "click")
    }

    /**
     * @param x1 起点 X
     * @param y1 起点 Y
     * @param x2 终点 X
     * @param y2 终点 Y
     * @param durationMs 总时长（毫秒）
     * @return JSON 响应字符串
     */
    fun injectSwipe(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = 300L
    ): String {
        // 起点和终点各加偏移
        val sx = x1 + randomGaussianOffset()
        val sy = y1 + randomGaussianOffset()
        val ex = x2 + randomGaussianOffset()
        val ey = y2 + randomGaussianOffset()

        val path = Path().apply {
            moveTo(sx, sy)

            // 分段绘制滑动轨迹，每段加入微小偏移
            for (i in 1..SWIPE_STEPS) {
                val t = i.toFloat() / SWIPE_STEPS
                val cx = lerp(sx, ex, t) + randomGaussianOffset() * 0.3f
                val cy = lerp(sy, ey, t) + randomGaussianOffset() * 0.3f
                lineTo(cx, cy)
            }
        }

        val gesture = buildGestureDescription(path, durationMs)
        return dispatchAndAwait(gesture, "swipe")
    }

    /**
     * 生成随机偏移（Box-Muller 变换）
     */
    private fun randomGaussianOffset(): Float {
        return (gaussianRandom() * GAUSSIAN_OFFSET_STD).toFloat()
    }

    /**
     * 生成按压时长（限制在合理范围内）
     */
    private fun randomPressDuration(): Long {
        val raw = (PRESS_DURATION_MEAN_MS + gaussianRandom() * PRESS_DURATION_STD_MS).toLong()
        return raw.coerceIn(PRESS_DURATION_MIN_MS, PRESS_DURATION_MAX_MS)
    }

    /**
     * Box-Muller 变换生成标准正态随机数
     */
    private fun gaussianRandom(): Double {
        val u1 = Random.nextDouble()
        val u2 = Random.nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    private fun buildGestureDescription(path: Path, durationMs: Long): GestureDescription {
        val strokeDescription = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceAtLeast(1L)
        )

        return GestureDescription.Builder()
            .addStroke(strokeDescription)
            .build()
    }

    private fun dispatchAndAwait(
        gesture: GestureDescription,
        actionName: String
    ): String {
        return try {
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    AppLogger.d(TAG, "手势 [$actionName] 执行完成")
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    AppLogger.w(TAG, "手势 [$actionName] 被取消")
                }
            }

            val dispatched = dispatchGesture(gesture, callback, null)
            if (dispatched) {
                """{"status": "ok"}"""
            } else {
                AppLogger.e(TAG, "手势 [$actionName] 分发失败")
                """{"status": "error", "message": "gesture dispatch failed"}"""
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "手势 [$actionName] 执行异常", e)
            """{"status": "error", "message": "${e.message}"}"""
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
