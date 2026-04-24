package com.pengxh.daily.app.utils

import com.pengxh.daily.app.sqlite.bean.DailyTaskBean

/**
 * 应用内事件定义
 * 统一使用EventBus进行应用内组件通信
 */
sealed class ApplicationEvent {
    /**
     * 蒙版视图控制事件
     */
    object ShowMaskView : ApplicationEvent()
    object HideMaskView : ApplicationEvent()

    /**
     * 监听器状态事件
     */
    object ListenerConnected : ApplicationEvent()
    object ListenerDisconnected : ApplicationEvent()

    /**
     * 任务控制事件
     */
    object StartDailyTask : ApplicationEvent()
    object StopDailyTask : ApplicationEvent()
    object SetResetTaskTime : ApplicationEvent()
    data class UpdateResetTickTime(val countDownTime: String) : ApplicationEvent()
    object ResetDailyTask : ApplicationEvent()
    object DailyTaskStarted : ApplicationEvent()
    object DailyTaskStopped : ApplicationEvent()
    object DailyTaskCompleted : ApplicationEvent()
    data class DailyTaskExecuting(
        val taskIndex: Int,
        val task: DailyTaskBean,
        val realTime: String
    ) : ApplicationEvent()

    data class DailyTaskExecutionError(val message: String) : ApplicationEvent()

    /**
     * 悬浮窗控制事件
     */
    object ShowFloatingWindow : ApplicationEvent()
    object HideFloatingWindow : ApplicationEvent()
    data class StartCountdownTime(val isRemoteCommand: Boolean) : ApplicationEvent()
    data class UpdateFloatingViewTime(val tick: Int) : ApplicationEvent()
    data class SetTaskOvertime(val time: Int) : ApplicationEvent()

    /**
     * 导航事件
     */
    object GoBackMainActivity : ApplicationEvent()

    /**
     * 截屏事件
     */
    object CaptureScreen : ApplicationEvent()
    data class CaptureCompleted(val imagePath: String) : ApplicationEvent()


    /**
     * 投影截屏事件
     */
    object ProjectionReady : ApplicationEvent()
    object ProjectionFailed : ApplicationEvent()
}
