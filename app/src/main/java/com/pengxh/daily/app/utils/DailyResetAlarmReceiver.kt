package com.pengxh.daily.app.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.pengxh.daily.app.service.ForegroundRunningService

class DailyResetAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DailyResetAlarmScheduler.ACTION_DAILY_RESET) {
            return
        }

        DailyResetAlarmScheduler.schedule(context)
        TaskScheduler.notifyResetWaiters()
        ContextCompat.startForegroundService(
            context, Intent(context, ForegroundRunningService::class.java)
        )
    }
}