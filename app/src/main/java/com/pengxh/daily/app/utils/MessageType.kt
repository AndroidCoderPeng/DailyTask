package com.pengxh.daily.app.utils

enum class MessageType(val action: String) {
    /**
     * - 重置任务
     * - 更新重置任务计时器
     * */
    RESET_DAILY_TASK("com.pengxh.daily.app.BROADCAST_RESET_DAILY_TASK_ACTION"),
    UPDATE_RESET_TICK_TIME("com.pengxh.daily.app.BROADCAST_UPDATE_RESET_TICK_TIME_ACTION"),

    /**
     * - 设置重置任务时间
     * - 设置停止在目标应用界面上的超时时间
     * */
    SET_RESET_TASK_TIME("com.pengxh.daily.app.BROADCAST_SET_RESET_TASK_TIME_ACTION"),
    SET_DING_DING_OVERTIME("com.pengxh.daily.app.BROADCAST_SET_DING_DING_OVERTIME_ACTION"),

    /**
     * - 显示悬浮窗
     * - 隐藏悬浮窗
     * */
    SHOW_FLOATING_WINDOW("com.pengxh.daily.app.BROADCAST_SHOW_FLOATING_WINDOW_ACTION"),
    HIDE_FLOATING_WINDOW("com.pengxh.daily.app.BROADCAST_HIDE_FLOATING_WINDOW_ACTION"),

    /**
     * - 显示蒙版
     * - 隐藏蒙版
     * */
    SHOW_MASK_VIEW("com.pengxh.daily.app.BROADCAST_SHOW_MASK_VIEW_ACTION"),
    HIDE_MASK_VIEW("com.pengxh.daily.app.BROADCAST_HIDE_MASK_VIEW_ACTION"),

    /**
     * - 通知监听器已连接
     * - 监听器已断开
     * */
    NOTICE_LISTENER_CONNECTED("com.pengxh.daily.app.BROADCAST_NOTICE_LISTENER_CONNECTED_ACTION"),
    NOTICE_LISTENER_DISCONNECTED("com.pengxh.daily.app.BROADCAST_NOTICE_LISTENER_DISCONNECTED_ACTION"),

    /**
     * - 开始每日任务
     * - 停止每日任务
     * */
    START_DAILY_TASK("com.pengxh.daily.app.BROADCAST_START_DAILY_TASK_ACTION"),
    STOP_DAILY_TASK("com.pengxh.daily.app.BROADCAST_STOP_DAILY_TASK_ACTION"),

    /**
     * - 开始倒计时
     * - 取消倒计时
     * */
    START_COUNT_DOWN_TIMER("com.pengxh.daily.app.BROADCAST_START_COUNT_DOWN_TIMER_ACTION"),
    CANCEL_COUNT_DOWN_TIMER("com.pengxh.daily.app.BROADCAST_CANCEL_COUNT_DOWN_TIMER_ACTION"),

    /**
     * - 更新悬浮窗倒计时
     * */
    UPDATE_FLOATING_WINDOW_TIME("com.pengxh.daily.app.BROADCAST_UPDATE_FLOATING_WINDOW_TIME_ACTION");

    companion object {
        fun fromAction(action: String): MessageType? {
            return entries.find { it.action == action }
        }
    }
}