package com.pengxh.daily.app.utils

/**
 * 通知监听服务 → UI 事件
 * 通过 NotificationMonitorService.events: SharedFlow 传递
 */
sealed class MonitorEvent {
    /**
     * 打卡成功通知
     * */
    data object ClockInSuccess : MonitorEvent()

    /**
     * 远程"执行任务"指令
     * */
    data object StartTaskCommand : MonitorEvent()

    /**
     * 远程"终止任务"指令
     * */
    data object StopTaskCommand : MonitorEvent()

    /**
     * 远程"息屏"指令
     * */
    data object ShowMaskCommand : MonitorEvent()

    /**
     * 远程"亮屏"指令
     * */
    data object HideMaskCommand : MonitorEvent()

    /**
     * 远程"截屏"指令
     * */
    data object AppOpenedForScreenshot : MonitorEvent()
}