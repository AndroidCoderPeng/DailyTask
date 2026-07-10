package com.pengxh.daily.app.utils

import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
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
    private var timeoutTimer: CountDownTimer? = null
    private var timeoutSeconds: Int = 0
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
        timeoutSeconds = try {
            SaveKeyValues.loadInt(Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME)
        } catch (_: Exception) {
            Constant.DEFAULT_OVER_TIME
        }

        timeoutTimer = object : CountDownTimer(timeoutSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val tick = (millisUntilFinished / 1000).toInt()
                // 更新悬浮窗倒计时
                FloatingWindowController.updateTime(tick)

                // 启用截屏
                val resultSource = SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, 0)
                if (resultSource == 1) {
                    if (tick <= 3 && !hasCaptured) {
                        hasCaptured = true
                        EventBus.getDefault().post(ApplicationEvent.CaptureScreen)
                    }
                }
            }

            override fun onFinish() {
                mainHandler.post {
                    onTimeout?.invoke()
                }
                timeoutTimer = null
                hasCaptured = false
            }
        }
        timeoutTimer?.start()
    }

    /**
     * 取消超时定时器
     */
    fun cancelTimeoutTimer() {
        timeoutTimer?.cancel()
        timeoutTimer = null
    }

    /**
     * 销毁资源
     */
    fun destroy() {
        onTimeout = null
        cancelTimeoutTimer()
    }
}
