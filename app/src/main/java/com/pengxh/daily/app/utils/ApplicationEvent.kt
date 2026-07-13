package com.pengxh.daily.app.utils

sealed class ApplicationEvent {
    /**
     * 任务控制事件
     */
    object ResetDailyTask : ApplicationEvent()
}
