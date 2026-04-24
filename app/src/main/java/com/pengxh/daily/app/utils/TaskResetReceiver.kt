package com.pengxh.daily.app.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pengxh.daily.app.service.CountDownTimerService
import com.pengxh.kt.lite.utils.SaveKeyValues

class TaskResetReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val autoStart = SaveKeyValues.getValue(Constant.TASK_AUTO_START_KEY, true) as Boolean
        if (autoStart) {
            CountDownTimerService.startDailyTask(context)
        }

        // 重新注册明天同一时刻的 Alarm（循环触发）
        val resetHour = SaveKeyValues.getValue(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        ) as Int
        AlarmScheduler.schedule(context, resetHour)
    }
}
