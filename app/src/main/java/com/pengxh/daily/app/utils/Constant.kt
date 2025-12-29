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

    const val DING_DING = "com.alibaba.android.rimet" // 钉钉
    const val WECHAT = "com.tencent.mm" // 微信
    const val WEWORK = "com.tencent.wework" // 企业微信
    const val QQ = "com.tencent.mobileqq" // QQ
    const val TIM = "com.tencent.tim" // TIM
    const val ZFB = "com.eg.android.AlipayGphone" // 支付宝

    const val FOREGROUND_RUNNING_SERVICE_TITLE = "为保证程序正常运行，请勿移除此通知"
    const val DEFAULT_RESET_HOUR = 0
    const val DEFAULT_OVER_TIME = 30

    // 目标APP
    fun getTargetApp(): String {
        return DING_DING
    }
}