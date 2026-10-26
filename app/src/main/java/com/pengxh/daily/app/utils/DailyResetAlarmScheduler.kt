package com.pengxh.daily.app.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object DailyResetAlarmScheduler {
    const val ACTION_DAILY_RESET = "com.pengxh.daily.app.action.DAILY_RESET"

    private const val REQUEST_CODE = 1002

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, DailyResetAlarmReceiver::class.java).setAction(ACTION_DAILY_RESET),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val resetHour = AppConfig.loadInt(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        )

        alarmManager.cancel(pendingIntent)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, nextResetTimeMillis(resetHour), pendingIntent
        )
    }

    private fun nextResetTimeMillis(resetHour: Int): Long {
        return Calendar.getInstance().run {
            set(Calendar.HOUR_OF_DAY, resetHour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DATE, 1)
            }
            timeInMillis
        }
    }
}
