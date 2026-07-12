package com.pengxh.daily.app.utils

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pengxh.kt.lite.utils.SaveKeyValues
import org.greenrobot.eventbus.EventBus

/**
 * 超时定时器管理器（单例）
 *
 * 用于管理超时定时器，并在超时时触发回调
 */
object TimeoutTimerManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    private var screenshotRunnable: Runnable? = null
    private var targetElapsedTime: Long = 0

    @Volatile
    private var hasCaptured = false

    var onTimeout: (() -> Unit)? = null

    // ========== 打卡场景 ==========
    fun startTimeoutTimer() {
        hasCaptured = false
        // 取消之前的定时器，防止重复创建
        cancelTimeoutTimer()

        val timeoutSeconds =
            SaveKeyValues.loadInt(Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME)
        targetElapsedTime = SystemClock.elapsedRealtime() + timeoutSeconds * 1000L

        tickRunnable = object : Runnable {
            override fun run() {
                val remaining = targetElapsedTime - SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    mainHandler.post { onTimeout?.invoke() }
                    hasCaptured = false
                    return
                }

                val tick = (remaining / 1000).toInt()
                FloatingWindowController.updateTime(tick)

                // 最后 5 秒兜底截屏
                if (tick <= 5 && !hasCaptured) {
                    val resultSource = SaveKeyValues.loadInt(
                        Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX
                    )
                    if (resultSource == 1) {
                        hasCaptured = true
                        EventBus.getDefault().post(ApplicationEvent.CaptureScreen)
                    }
                }

                val delay = minOf(1000L, remaining).coerceAtLeast(1)
                mainHandler.postDelayed(this, delay)
            }
        }
        tickRunnable?.let { mainHandler.post(it) }
    }

    // ========== 远程截屏场景 ==========
    fun scheduleScreenshot(delaySeconds: Int, onComplete: () -> Unit) {
        cancelScreenshotTimer()
        hasCaptured = false

        val targetTime = SystemClock.elapsedRealtime() + delaySeconds * 1000L

        screenshotRunnable = object : Runnable {
            override fun run() {
                val remaining = targetTime - SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    // 等待结束，触发截屏
                    if (!hasCaptured) {
                        hasCaptured = true
                        EventBus.getDefault().post(ApplicationEvent.CaptureScreen)
                    }
                    // 截屏完成后回调
                    mainHandler.post { onComplete() }
                    hasCaptured = false
                    return
                }

                // 可以顺便更新悬浮窗显示等待中
                val tick = (remaining / 1000).toInt()
                FloatingWindowController.updateTime(tick)

                val delay = minOf(1000L, remaining).coerceAtLeast(1)
                mainHandler.postDelayed(this, delay)
            }
        }
        screenshotRunnable?.let { mainHandler.post(it) }
    }

    private fun cancelScreenshotTimer() {
        screenshotRunnable?.let { mainHandler.removeCallbacks(it) }
        screenshotRunnable = null
    }

    /**
     * 取消超时定时器
     */
    fun cancelTimeoutTimer() {
        tickRunnable?.let { mainHandler.removeCallbacks(it) }
        tickRunnable = null
    }

    /**
     * 销毁资源
     */
    fun destroy() {
        onTimeout = null
        cancelTimeoutTimer()
        cancelScreenshotTimer()
    }
}
