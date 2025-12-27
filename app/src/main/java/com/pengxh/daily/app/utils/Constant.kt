package com.pengxh.daily.app.utils

/**
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/29 12:42
 */
object Constant {
    const val RESET_TIME_KEY = "RESET_TIME_KEY"
    const val STAY_DD_TIMEOUT_KEY = "STAY_DD_TIMEOUT_KEY"
    const val GESTURE_DETECTOR_KEY = "GESTURE_DETECTOR_KEY"
    const val BACK_TO_HOME_KEY = "BACK_TO_HOME_KEY"
    const val TASK_NAME_KEY = "TASK_KEY"
    const val RANDOM_TIME_KEY = "RANDOM_TIME_KEY"
    const val RANDOM_MINUTE_RANGE_KEY = "RANDOM_MINUTE_RANGE_KEY"
    const val TASK_AUTO_START_KEY = "TASK_AUTO_START_KEY"
    const val NEED_START_TASK_KEY = "NEED_START_TASK_KEY"

    const val BROADCAST_RESET_TASK_ACTION =
        "com.pengxh.daily.app.BROADCAST_RESET_TASK_ACTION" // 重置任务
    const val BROADCAST_UPDATE_RESET_TICK_TIME_ACTION =
        "com.pengxh.daily.app.BROADCAST_UPDATE_RESET_TICK_TIME_ACTION" // 更新重置任务计时器
    const val BROADCAST_UPDATE_FLOATING_WINDOW_TICK_TIME_ACTION =
        "com.pengxh.daily.app.BROADCAST_UPDATE_FLOATING_WINDOW_TICK_TIME_ACTION" // 更新悬浮窗倒计时
    const val BROADCAST_SHOW_FLOATING_WINDOW_ACTION =
        "com.pengxh.daily.app.BROADCAST_SHOW_FLOATING_WINDOW_ACTION" // 显示悬浮窗
    const val BROADCAST_HIDE_FLOATING_WINDOW_ACTION =
        "com.pengxh.daily.app.BROADCAST_HIDE_FLOATING_WINDOW_ACTION" // 隐藏悬浮窗
    const val BROADCAST_SHOW_MASK_VIEW_ACTION =
        "com.pengxh.daily.app.BROADCAST_SHOW_MASK_VIEW_ACTION" // 显示蒙版
    const val BROADCAST_HIDE_MASK_VIEW_ACTION =
        "com.pengxh.daily.app.BROADCAST_HIDE_MASK_VIEW_ACTION" // 隐藏蒙版
    const val BROADCAST_NOTICE_LISTENER_CONNECTED_ACTION =
        "com.pengxh.daily.app.BROADCAST_NOTICE_LISTENER_CONNECTED_ACTION" // 通知监听器已连接
    const val BROADCAST_NOTICE_LISTENER_DISCONNECTED_ACTION =
        "com.pengxh.daily.app.BROADCAST_NOTICE_LISTENER_DISCONNECTED_ACTION" // 监听器已断开
    const val BROADCAST_START_DAILY_TASK_ACTION =
        "com.pengxh.daily.app.BROADCAST_START_DAILY_TASK_ACTION" // 开始执行任务
    const val BROADCAST_STOP_DAILY_TASK_ACTION =
        "com.pengxh.daily.app.BROADCAST_STOP_DAILY_TASK_ACTION" // 停止执行任务
    const val BROADCAST_START_COUNT_DOWN_TIMER_ACTION =
        "com.pengxh.daily.app.BROADCAST_START_COUNT_DOWN_TIMER_ACTION" // 开始倒计时
    const val BROADCAST_CANCEL_COUNT_DOWN_TIMER_ACTION =
        "com.pengxh.daily.app.BROADCAST_CANCEL_COUNT_DOWN_TIMER_ACTION" // 取消倒计时

    const val DING_DING = "com.alibaba.android.rimet" // 钉钉
    const val WECHAT = "com.tencent.mm" // 微信
    const val WEWORK = "com.tencent.wework" // 企业微信
    const val QQ = "com.tencent.mobileqq" // QQ
    const val TIM = "com.tencent.tim" // TIM
    const val ZFB = "com.eg.android.AlipayGphone" // 支付宝

    // 目标APP
    const val TARGET_APP = DING_DING

    const val FOREGROUND_RUNNING_SERVICE_TITLE = "为保证程序正常运行，请勿移除此通知"
    const val DEFAULT_RESET_HOUR = 0
    const val DEFAULT_OVER_TIME = 30
}