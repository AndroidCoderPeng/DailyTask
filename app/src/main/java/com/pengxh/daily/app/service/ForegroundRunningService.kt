package com.pengxh.daily.app.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.CountDownTimer
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.event.UpdateTaskResetTimeEvent
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.kt.lite.utils.LiteKitConstant
import com.pengxh.kt.lite.utils.SaveKeyValues
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
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
    private var taskTimer: CountDownTimer? = null
    private var isTimerRunning = false

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        EventBus.getDefault().register(this)

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

        // 启动重置任务计时器
        val hour = SaveKeyValues.getValue(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        ) as Int
        startResetTaskTimer(hour)

        // 监听时间，系统级广播，每分钟触发一次。系统广播接收器不需要 RECEIVER_EXPORTED 或 RECEIVER_NOT_EXPORTED 标志
        IntentFilter(Intent.ACTION_TIME_TICK).apply {
            registerReceiver(systemBroadcastReceiver, this)
        }
    }

    private val systemBroadcastReceiver by lazy {
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
                                        "循环任务已手动停止，不再自动重置每日任务！如需恢复，可通过远程消息发送【启动】指令。"
                                }
                                LogFileManager.writeLog(message)
                                emailManager.sendEmail("循环任务状态通知", message, false)
                            }
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
    }

    @Subscribe
    fun updateTaskResetTime(event: UpdateTaskResetTimeEvent) {
        // 重置任务计时器
        startResetTaskTimer(event.hour)
    }

    private fun startResetTaskTimer(hour: Int) {
        // 先取消之前的计时器
        taskTimer?.cancel()

        Log.d(kTag, "重置任务时间为：$hour 点")

        val currentDiffSeconds = resetTaskSeconds(hour)
        taskTimer = object : CountDownTimer(currentDiffSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                val message = String.format(
                    Locale.getDefault(), "%s后刷新每日任务", seconds.formatTime()
                )
                Intent(Constant.BROADCAST_UPDATE_RESET_TICK_TIME_ACTION).apply {
                    putExtra(LiteKitConstant.BROADCAST_MESSAGE_KEY, message)
                    sendBroadcast(this)
                }
            }

            override fun onFinish() {
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
        EventBus.getDefault().unregister(this)
        unregisterReceiver(systemBroadcastReceiver)
        taskTimer?.cancel()
        taskTimer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}