package com.pengxh.daily.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.util.Calendar

/**
 * APP前台服务，降低APP被系统杀死的可能性
 * */
class ForegroundRunningService : Service() {

    private val kTag = "ForegroundService"
    private val notificationId = Int.MAX_VALUE
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, "foreground_running_service_channel").apply {
            setSmallIcon(R.mipmap.ic_launcher)
            setContentText(Constant.FOREGROUND_RUNNING_SERVICE_TITLE)
            setPriority(NotificationCompat.PRIORITY_HIGH) // 设置通知优先级
            setOngoing(true)
            setOnlyAlertOnce(true)
            setSilent(true)
            setCategory(NotificationCompat.CATEGORY_SERVICE)
            setShowWhen(true)
            setSound(null) // 禁用声音
            setVibrate(null) // 禁用振动
        }
    }

    override fun onCreate() {
        super.onCreate()
        val name = "${resources.getString(R.string.app_name)}前台服务"
        val channel = NotificationChannel(
            "foreground_running_service_channel", name, NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Channel for Foreground Running Service"
        }
        notificationManager.createNotificationChannel(channel)
        notificationBuilder.build().apply {
            notificationManager.notify(notificationId, this)
        }

        // 监听时间，系统级广播，每分钟触发一次
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
        }
        registerReceiver(timeReceiver, filter)
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let {
                if (it == Intent.ACTION_TIME_TICK) {
                    val hour = SaveKeyValues.getValue(
                        Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
                    ) as Int

                    val calendar = Calendar.getInstance()
                    val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
                    Log.d(kTag, "当前时间: $currentHour, 计划时间: $hour")

                    if (currentHour >= hour) {
                        sendBroadcast(Intent(Constant.BROADCAST_RESET_TASK_ACTION))
                    } else {
                        Log.d(kTag, "onReceive: 设定时间未到，继续执行当天任务")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        unregisterReceiver(timeReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}