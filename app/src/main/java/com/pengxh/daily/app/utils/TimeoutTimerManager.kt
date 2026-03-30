package com.pengxh.daily.app.utils

import android.os.CountDownTimer
import android.os.Handler
import com.pengxh.kt.lite.utils.SaveKeyValues
import org.greenrobot.eventbus.EventBus

/**
 * 超时定时器管理器
 *
 * 职责：
 * 1. 管理打卡超时定时器的生命周期
 * 2. 向悬浮窗广播倒计时更新
 * 3. 处理超时后的逻辑（返回主界面、发送异常邮件）
 * 4. 提供定时器取消接口
 *
 * @param mainHandler 主线程Handler
 */
class TimeoutTimerManager(private val mainHandler: Handler) {

    private var timeoutTimer: CountDownTimer? = null
    private var timeoutSeconds: Int = 0
    private var hasCaptured = false

    /**
     * 启动超时定时器
     *
     * @param onTimeout 超时回调，当倒计时结束时触发
     */
    fun startTimeoutTimer(onTimeout: () -> Unit) {
        hasCaptured = false
        // 取消之前的定时器，防止重复创建
        cancelTimeoutTimer()

        // 获取超时时长配置（单位：秒）
        timeoutSeconds = try {
            val value = SaveKeyValues.getValue(
                Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
            )
            (value as? Int) ?: Constant.DEFAULT_OVER_TIME
        } catch (_: Exception) {
            Constant.DEFAULT_OVER_TIME
        }

        timeoutTimer = object : CountDownTimer(timeoutSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val tick = (millisUntilFinished / 1000).toInt()
                // 更新悬浮窗倒计时
                EventBus.getDefault().post(ApplicationEvent.UpdateFloatingViewTime(tick))

                // 启用截屏
                val resultSource = SaveKeyValues.getValue(Constant.RESULT_SOURCE_KEY, 0) as Int
                if (resultSource == 1) {
                    if (tick <= 3 && !hasCaptured) {
                        hasCaptured = true
                        EventBus.getDefault().post(ApplicationEvent.CaptureScreen)
                    }
                }
            }

            override fun onFinish() {
                mainHandler.post {
                    onTimeout()
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
        cancelTimeoutTimer()
    }
}