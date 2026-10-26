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
     * 远程"息屏"指令
     * */
    data object ShowMaskCommand : MonitorEvent()

    /**
     * 远程"亮屏"指令
     * */
    data object HideMaskCommand : MonitorEvent()

    /**
     * 服务端远程操作完成后返回应用
     * */
    data object ReturnToApp : MonitorEvent()
}
