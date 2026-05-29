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
            // 用 postSticky 保证 MainActivity 未注册时事件不丢失，启动后仍可收到
            EventBus.getDefault().postSticky(ApplicationEvent.ResetDailyTask)
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
        // 每日重置时清掉运行状态，防止第二天打开 app 还显示"停止"
        SaveKeyValues.putValue(Constant.TASK_RUNNING_STATE_KEY, false)
        LogFileManager.writeLog("标记 $today 已重置")
    }
}