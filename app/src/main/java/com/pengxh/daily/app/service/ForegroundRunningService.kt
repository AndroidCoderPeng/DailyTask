package com.pengxh.daily.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.util.Calendar

/**
 * APP前台服务，降低APP被系统杀死的可能性
 * */
class ForegroundRunningService : Service() {
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
    private val emailManager by lazy { EmailManager(this) }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timeReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(timeReceiver, filter)
        }
    }

    private val timeReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.action?.let {
                    if (it == Intent.ACTION_TIME_TICK) {
                        val hour = SaveKeyValues.getValue(
                            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
                        ) as Int
                        val calendar = Calendar.getInstance()
                        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
                        if (currentHour == hour) {
                            val autoStart = SaveKeyValues.getValue(
                                Constant.TASK_AUTO_START_KEY, true
                            ) as Boolean
                            val currentMinute = calendar.get(Calendar.MINUTE)
                            // 只在整点执行
                            if (currentMinute == 0) {
                                var message = ""
                                if (autoStart) {
                                    message = "达到任务计划时间，重置每日任务。"
                                    sendBroadcast(Intent(Constant.BROADCAST_RESET_TASK_ACTION))
                                } else {
                                    message =
                                        "循环任务已手动停止，将不再自动重置每日任务！如需恢复，可通过远程消息发送【启动】指令。"
                                }
                                LogFileManager.writeLog(message)
                                emailManager.sendEmail("循环任务状态通知", message, false)
                            }
                        }
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