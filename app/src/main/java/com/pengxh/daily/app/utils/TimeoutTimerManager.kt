package com.pengxh.daily.app.utils

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pengxh.kt.lite.utils.SaveKeyValues
import org.greenrobot.eventbus.EventBus

/**
 * 超时定时器管理器（单例）
 *
 * 职责：
 * 1. 管理打卡超时定时器的生命周期
 * 2. 向悬浮窗广播倒计时更新
 * 3. 处理超时后的逻辑（回调给 MainActivity）
 * 4. 提供定时器取消接口
 */
object TimeoutTimerManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    private var targetElapsedTime: Long = 0
    private var hasCaptured = false

    /**
     * 超时回调，由 MainActivity 在启动时注入
     * */
    var onTimeout: (() -> Unit)? = null

    /**
     * 启动超时定时器
     */
    fun startTimeoutTimer() {
        hasCaptured = false
        // 取消之前的定时器，防止重复创建
        cancelTimeoutTimer()

        // 获取超时时长配置（单位：秒）
        val timeoutSeconds = try {
            SaveKeyValues.loadInt(Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME)
        } catch (_: Exception) {
            Constant.DEFAULT_OVER_TIME
        }

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
    }
}
