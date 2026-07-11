package com.pengxh.daily.app.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pengxh.kt.lite.utils.SaveKeyValues
import org.greenrobot.eventbus.EventBus
import java.time.LocalDate

class TaskResetReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val today = "${LocalDate.now()}"
        if (hasResetToday(today)) {
            LogFileManager.writeLog("今天已经执行过重置，跳过")
            return
        }

        // 先标记已重置、清除运行状态，再发送事件
        SaveKeyValues.saveString(Constant.LAST_RESET_DATE_KEY, today)
        LogFileManager.writeLog("标记 $today 已重置")

        val autoStart = SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
        if (autoStart) {
            // 用 postSticky 保证 MainActivity 未注册时事件不丢失，启动后仍可收到
            EventBus.getDefault().postSticky(ApplicationEvent.ResetDailyTask)
        }

        // 重新注册明天同一时刻的 Alarm（循环触发）
        val resetHour = SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        AlarmScheduler.schedule(context, resetHour)
    }

    private fun hasResetToday(today: String): Boolean {
        val lastResetDate = SaveKeyValues.loadString(Constant.LAST_RESET_DATE_KEY, "")
        return today == lastResetDate
    }
}