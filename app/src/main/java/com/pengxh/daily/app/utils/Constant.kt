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
    const val RESET_TIME_KEY = "RESET_TIME_KEY" // 任务重置时间点(Int)
    const val STAY_OVERTIME_KEY = "STAY_OVERTIME_KEY" // 打卡停留在目标APP的时间(Int)
    const val TIME_RANGE_KEY = "TIME_RANGE_KEY" // 随机时间范围[0,range](Int)
    const val MSG_CHANNEL_KEY = "MSG_CHANNEL_KEY" // 消息渠道：0-邮件，1-企业微信(Int)
    const val TARGET_APP_KEY = "TARGET_APP_KEY" // 目标应用(Int)

    const val REMOTE_COMMAND_KEY = "REMOTE_COMMAND_KEY" // 打卡远程消息指令(String)
    const val MESSAGE_TITLE_KEY = "MESSAGE_TITLE_KEY" // 打卡消息标题(String)
    const val WX_WEB_HOOK_KEY = "WX_WEB_HOOK_KEY" // 企业微信消息Key(String)

    const val GESTURE_DETECTOR_KEY = "GESTURE_DETECTOR_KEY" // 检测手势(Boolean)
    const val BACK_TO_HOME_KEY = "BACK_TO_HOME_KEY" // 返回桌面(Boolean)
    const val TASK_AUTO_RECYCLE_KEY = "TASK_AUTO_RECYCLE_KEY" // 任务每日自动循环(Boolean)
    const val RANDOM_TIME_KEY = "RANDOM_TIME_KEY" // 随机时间(Boolean)
    const val SKIP_HOLIDAY_KEY = "SKIP_HOLIDAY_KEY" // 跳过节假日(Boolean)
    const val POWER_SAVE_MODE_KEY = "POWER_SAVE_MODE_KEY" // 省电模式(Boolean)

    // 不导出的sp缓存
    const val LAST_RESET_DATE_KEY = "LAST_RESET_DATE_KEY"
    const val RESULT_SOURCE_KEY = "RESULT_SOURCE_KEY"

    // ============================================================
    // ConfigStore 键
    // ============================================================
    const val EMAIL_CONFIG_KEY = "emailConfig" // 邮箱配置

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
    const val DEFAULT_INDEX = -1
    const val DEFAULT_RESET_HOUR = 0
    const val DEFAULT_TIME_RANGE = 5
    const val DEFAULT_OVER_TIME = 30
    const val CAPTURE_IMAGE_SERVICE_NOTIFICATION_ID = 1001
    const val COUNTDOWN_TIMER_SERVICE_NOTIFICATION_ID = 1002
    const val FOREGROUND_RUNNING_SERVICE_NOTIFICATION_ID = 1003

    // 目标APP
    fun getTargetApp(): String {
        return when (SaveKeyValues.loadInt(TARGET_APP_KEY, 0)) {
            0 -> DING_DING
            1 -> WEWORK
            2 -> FEI_SHU
            3 -> MOBILE_M3
            else -> DING_DING
        }
    }
}