package com.pengxh.daily.app.utils

/**
 * 应用内事件定义
 * 统一使用EventBus进行应用内组件通信
 */
sealed class ApplicationEvent {
    /**
     * 任务控制事件
     */
    object StopDailyTask : ApplicationEvent()
    object SetResetTaskTime : ApplicationEvent()
    data class UpdateResetTickTime(val countDownTime: String) : ApplicationEvent()
    object ResetDailyTask : ApplicationEvent()

    /**
     * 截屏事件
     */
    object CaptureScreen : ApplicationEvent()

    /**
     * 投影截屏事件
     */
    object ProjectionReady : ApplicationEvent()
    object ProjectionFailed : ApplicationEvent()
    object ProjectionDestroyed : ApplicationEvent()

    /**
     * 倒计时通知更新
     */
    data class UpdateNotification(val text: String) : ApplicationEvent()
}
