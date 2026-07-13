package com.pengxh.daily.app.utils

/**
 * 应用内事件定义
 * 统一使用EventBus进行应用内组件通信
 */
sealed class ApplicationEvent {
    /**
     * 任务控制事件
     */
    object ResetDailyTask : ApplicationEvent()

    /**
     * 截屏事件
     */
    object CaptureScreen : ApplicationEvent()
}
