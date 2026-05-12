package com.pengxh.daily.app.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pengxh.kt.lite.utils.SaveKeyValues
import org.greenrobot.eventbus.EventBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskResetReceiver : BroadcastReceiver() {

    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA) }

    override fun onReceive(context: Context, intent: Intent?) {
        if (hasResetToday()) {
            LogFileManager.writeLog("今天已经执行过重置，跳过")
            return
        }

        val autoStart = SaveKeyValues.getValue(Constant.TASK_AUTO_START_KEY, true) as Boolean
        if (autoStart) {
            // 触发主界面重置任务
            EventBus.getDefault().post(ApplicationEvent.ResetDailyTask)
        }

        markTodayAsReset()

        // 重新注册明天同一时刻的 Alarm（循环触发）
        val resetHour = SaveKeyValues.getValue(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        ) as Int
        AlarmScheduler.schedule(context, resetHour)
    }

    private fun hasResetToday(): Boolean {
        val today = dateFormat.format(Date())
        val lastResetDate = SaveKeyValues.getValue(Constant.LAST_RESET_DATE_KEY, "") as String
        return today == lastResetDate
    }

    private fun markTodayAsReset() {
        val today = dateFormat.format(Date())
        SaveKeyValues.putValue(Constant.LAST_RESET_DATE_KEY, today)
        LogFileManager.writeLog("标记 $today 已重置")
    }
}