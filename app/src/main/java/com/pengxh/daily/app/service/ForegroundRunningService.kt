package com.pengxh.daily.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.utils.BroadcastManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageType
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.util.Calendar
import java.util.Locale


/**
 * APP前台服务，降低APP被系统杀死的可能性
 * */
class ForegroundRunningService : Service() {
    private val kTag = "ForegroundRunningService"
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
    private var systemBroadcastReceiver: BroadcastReceiver? = null
    private var isTaskReset = false
    private var isTimerRunning = false
    private var taskTimer: CountDownTimer? = null

    override fun onCreate() {
        super.onCreate()
        val name = "${resources.getString(R.string.app_name)}前台服务"
        val channel = NotificationChannel(
            "foreground_running_service_channel", name, NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Channel for Foreground Running Service"
        }
        notificationManager.createNotificationChannel(channel)
        val notification = notificationBuilder.build()
        startForeground(notificationId, notification)

        BroadcastManager.getDefault().registerReceiver(
            this, MessageType.SET_RESET_TASK_TIME.action, object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    // 重置任务计时器
                    val hour = intent?.getIntExtra("hour", 0) as Int
                    startResetTaskTimer(hour)
                }
            })

        // 启动重置任务计时器
        val hour = SaveKeyValues.getValue(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        ) as Int
        startResetTaskTimer(hour)

        // 监听时间，系统级广播，每分钟触发一次。
        systemBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.action?.let {
                    if (it == Intent.ACTION_TIME_TICK) {
                        val hour = SaveKeyValues.getValue(
                            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
                        ) as Int
                        val calendar = Calendar.getInstance()
                        if (calendar.get(Calendar.HOUR_OF_DAY) == hour) {
                            resetTask()
                        }

                        // 启动重置任务计时器
                        if (!isTimerRunning) {
                            startResetTaskTimer(hour)
                            isTimerRunning = true
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_TIME_TICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemBroadcastReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(systemBroadcastReceiver, filter)
        }
    }

    private fun resetTask() {
        if (!isTaskReset) {
            var message: String
            if (SaveKeyValues.getValue(Constant.TASK_AUTO_START_KEY, true) as Boolean) {
                message = "到达任务计划时间，重置每日任务。"
                BroadcastManager.getDefault().sendBroadcast(
                    this@ForegroundRunningService, MessageType.RESET_DAILY_TASK.action
                )
                isTaskReset = true
            } else {
                message = "每日任务已手动停止，不再自动重置！如需恢复，可通过远程消息发送【启动】指令。"
            }
            LogFileManager.writeLog(message)
            emailManager.sendEmail("循环任务状态通知", message, false)
        }
    }

    private fun startResetTaskTimer(hour: Int) {
        val currentDiffSeconds = resetTaskSeconds(hour)

        // 先取消之前的计时器
        taskTimer?.cancel()
        taskTimer = object : CountDownTimer(currentDiffSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                val message = String.format(
                    Locale.getDefault(), "%s后刷新每日任务", seconds.formatTime()
                )
                BroadcastManager.getDefault().sendBroadcast(
                    this@ForegroundRunningService,
                    MessageType.UPDATE_RESET_TICK_TIME.action,
                    mapOf("message" to message)
                )
            }

            override fun onFinish() {
                isTaskReset = false
                isTimerRunning = false
                taskTimer = null
            }
        }
        taskTimer?.start()
    }

    private fun resetTaskSeconds(hour: Int): Int {
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        // 设置今天的计划时间
        val todayTargetMillis = calendar.clone() as Calendar
        todayTargetMillis.set(Calendar.HOUR_OF_DAY, hour)
        todayTargetMillis.set(Calendar.MINUTE, 0)
        todayTargetMillis.set(Calendar.SECOND, 0)
        todayTargetMillis.set(Calendar.MILLISECOND, 0)

        // 根据当前时间决定计算哪一天的计划时间
        val targetMillis = if (currentHour < hour) {
            // 今天还没到计划时间
            todayTargetMillis.timeInMillis
        } else {
            // 今天已经过了计划时间，计算明天的
            todayTargetMillis.add(Calendar.DATE, 1)
            todayTargetMillis.timeInMillis
        }

        val delta = (targetMillis - System.currentTimeMillis()) / 1000
        return delta.toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        BroadcastManager.getDefault().unregisterReceiver(
            this, MessageType.SET_RESET_TASK_TIME.action
        )
        systemBroadcastReceiver?.let {
            unregisterReceiver(it)
        }
        systemBroadcastReceiver = null
        taskTimer?.cancel()
        taskTimer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}