package com.pengxh.daily.app.utils

/**
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/29 12:42
 */
object Constant {
    const val EMAIL_SEND_ADDRESS_KEY = "EMAIL_SEND_ADDRESS_KEY"
    const val EMAIL_SEND_CODE_KEY = "EMAIL_SEND_CODE_KEY"
    const val EMAIL_SEND_SERVER_KEY = "EMAIL_SEND_SERVER_KEY"
    const val EMAIL_SEND_PORT_KEY = "EMAIL_SEND_PORT_KEY"
    const val EMAIL_IN_BOX_KEY = "EMAIL_IN_BOX_KEY"
    const val EMAIL_TITLE_KEY = "EMAIL_TITLE_KEY"
    const val STAY_DD_TIMEOUT_KEY = "STAY_DD_TIMEOUT_KEY"
    const val BACK_TO_HOME_KEY = "BACK_TO_HOME_KEY"
    const val TASK_NAME_KEY = "TASK_KEY"
    const val RANDOM_TIME_KEY = "RANDOM_TIME_KEY"
    const val RESET_TIME_KEY = "RESET_TIME_KEY"

    const val TICK_TIME_CODE = 2024071701
    const val UPDATE_TICK_TIME_CODE = 2024071702

    const val NOTICE_LISTENER_CONNECTED_CODE = 2024090801
    const val NOTICE_LISTENER_DISCONNECTED_CODE = 2024090802

    const val HIDE_FLOATING_WINDOW_CODE = 2024112501
    const val SHOW_FLOATING_WINDOW_CODE = 2024112502

    const val START_TASK_CODE = 2024120801
    const val EXECUTE_NEXT_TASK_CODE = 2024120802
    const val COMPLETED_ALL_TASK_CODE = 2024120803

    const val START_COUNT_DOWN_TIMER_CODE = 2024121801
    const val CANCEL_COUNT_DOWN_TIMER_CODE = 2024121802

    const val SEND_EMAIL_SUCCESS_CODE = 2024122501
    const val SEND_EMAIL_FAILED_CODE = 2024122502

    const val START_DAILY_TASK_CODE = 2025030701
    const val STOP_DAILY_TASK_CODE = 2025030702

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
    const val DEFAULT_OVER_TIME = "30s"
}