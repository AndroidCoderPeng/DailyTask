package com.pengxh.daily.app.utils

import com.pengxh.kt.lite.utils.SaveKeyValues

/**
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/29 12:42
 */
object Constant {
    // ============================================================
    // SharedPreferences 键
    // ============================================================
    const val RESET_TIME_KEY = "RESET_TIME_KEY"
    const val STAY_DD_TIMEOUT_KEY = "STAY_DD_TIMEOUT_KEY"
    const val GESTURE_DETECTOR_KEY = "GESTURE_DETECTOR_KEY"
    const val BACK_TO_HOME_KEY = "BACK_TO_HOME_KEY"
    const val TASK_COMMAND_KEY = "TASK_COMMAND_KEY"
    const val RANDOM_TIME_KEY = "RANDOM_TIME_KEY"
    const val RANDOM_MINUTE_RANGE_KEY = "RANDOM_MINUTE_RANGE_KEY"
    const val TASK_AUTO_START_KEY = "TASK_AUTO_START_KEY"
    const val TARGET_APP_KEY = "TARGET_APP_KEY"
    const val MESSAGE_TITLE_KEY = "MESSAGE_TITLE_KEY"
    const val WX_WEB_HOOK_KEY = "WX_WEB_HOOK_KEY"
    const val CHANNEL_TYPE_KEY = "CHANNEL_TYPE_KEY"
    const val RESULT_SOURCE_KEY = "RESULT_SOURCE_KEY"

    // ============================================================
    // 目标应用
    // ============================================================
    const val DING_DING = "com.alibaba.android.rimet" // 钉钉
    const val WEWORK = "com.tencent.wework" // 企业微信
    const val FEI_SHU = "com.ss.android.lark" // 飞书
    const val MOBILE_M3 = "com.seeyon.cmp" // 移动办公M3

    // ============================================================
    // 消息指令
    // ============================================================
    const val WECHAT = "com.tencent.mm" // 微信
    const val QQ = "com.tencent.mobileqq" // QQ
    const val TIM = "com.tencent.tim" // TIM
    const val ZFB = "com.eg.android.AlipayGphone" // 支付宝

    // ============================================================
    // webhook
    // ============================================================
    const val WX_WEB_HOOK_URL = "https://qyapi.weixin.qq.com"

    // ============================================================
    // 其他默认值
    // ============================================================
    const val DEFAULT_RESET_HOUR = 0
    const val DEFAULT_OVER_TIME = 30
    const val CAPTURE_IMAGE_SERVICE_NOTIFICATION_ID = 1001
    const val COUNTDOWN_TIMER_SERVICE_NOTIFICATION_ID = 1002
    const val FOREGROUND_RUNNING_SERVICE_NOTIFICATION_ID = 1003

    // 目标APP
    fun getTargetApp(): String {
        return when (SaveKeyValues.getValue(TARGET_APP_KEY, 0) as Int) {
            0 -> DING_DING
            1 -> WEWORK
            2 -> FEI_SHU
            3 -> MOBILE_M3
            else -> DING_DING
        }
    }
}