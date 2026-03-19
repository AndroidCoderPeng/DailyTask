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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.HttpRequestManager
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.kt.lite.utils.SaveKeyValues
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Calendar
import java.util.Locale

/**
 * APP前台服务，降低APP被系统杀死的可能性
 * */
class ForegroundRunningService : Service() {
    private val kTag = "ForegroundRunningService"
    private val httpRequestManager by lazy { HttpRequestManager(this) }
    private val emailManager by lazy { EmailManager() }

    @Volatile
    private var isTaskReset = false

    @Volatile
    private var isTimerRunning = false
    private var taskTimer: CountDownTimer? = null

    private fun resetTask() {
        if (!isTaskReset) {
            var message: String
            if (SaveKeyValues.getValue(Constant.TASK_AUTO_START_KEY, true) as Boolean) {
                EventBus.getDefault().post(ApplicationEvent.ResetDailyTask)
                message = "到达任务计划时间，重置每日任务"
            } else {
                message = "任务已手动停止，不再自动重置！如需恢复，可通过远程消息发送【开始循环】指令"
            }
            LogFileManager.writeLog(message)

            val type = SaveKeyValues.getValue(Constant.CHANNEL_TYPE_KEY, -1) as Int
            when (type) {
                0 -> {
                    // 企业微信
                    httpRequestManager.sendMessage("循环任务状态通知", message)
                }

                1 -> {
                    // QQ邮箱
                    emailManager.sendEmail("循环任务状态通知", message, false)
                }

                else -> {
                    Log.d(kTag, "sendChannelMessage: 消息渠道不支持")
                }
            }

            isTaskReset = true

            // 重置任务计时器
            val hour = SaveKeyValues.getValue(
                Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
            ) as Int
            startResetTaskTimer(hour)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val notificationManager = getSystemService(NotificationManager::class.java)
        val name = "${resources.getString(R.string.app_name)}前台服务"
        val channel = NotificationChannel(
            "foreground_running_service_channel", name, NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Channel for Foreground Running Service"
        }
        notificationManager.createNotificationChannel(channel)
        val notificationBuilder =
            NotificationCompat.Builder(this, "foreground_running_service_channel").apply {
                setSmallIcon(R.mipmap.ic_launcher)
                setContentText(Constant.FOREGROUND_RUNNING_SERVICE_TITLE)
                setPriority(NotificationCompat.PRIORITY_LOW) // 设置通知优先级
                setOngoing(true)
                setOnlyAlertOnce(true)
                setSilent(true)
                setCategory(NotificationCompat.CATEGORY_SERVICE)
                setShowWhen(true)
                setSound(null) // 禁用声音
                setVibrate(null) // 禁用振动
            }
        val notification = notificationBuilder.build()
        startForeground(1002, notification)

        val filter = IntentFilter(Intent.ACTION_TIME_TICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemBroadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(systemBroadcastReceiver, filter)
        }

        EventBus.getDefault().register(this)

        // 启动重置任务计时器
        val hour = SaveKeyValues.getValue(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        ) as Int
        startResetTaskTimer(hour)
    }

    private val systemBroadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.action?.let {
                    // 监听时间，系统级广播，每分钟触发一次。
                    if (it == Intent.ACTION_TIME_TICK) {
                        val hour = SaveKeyValues.getValue(
                            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
                        ) as Int
                        val calendar = Calendar.getInstance()
                        if (calendar.get(Calendar.HOUR_OF_DAY) == hour) {
                            resetTask()
                        }
                    }
                }
            }
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun handleApplicationEvent(event: ApplicationEvent) {
        if (event is ApplicationEvent.SetResetTaskTime) {
            // 重置任务计时器
            startResetTaskTimer(event.hour)
        }
    }

    private fun startResetTaskTimer(hour: Int) {
        val currentDiffSeconds = resetTaskSeconds(hour)
        updateResetTimeView(currentDiffSeconds)

        // 先取消之前的计时器
        taskTimer?.cancel()
        isTimerRunning = false

        taskTimer = object : CountDownTimer(currentDiffSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                // 每分钟发送一次广播，更省电
                if (seconds % 60 == 0) {
                    updateResetTimeView(seconds)
                }
            }

            override fun onFinish() {
                isTaskReset = false
                isTimerRunning = false
                taskTimer = null
            }
        }
        isTimerRunning = true
        taskTimer?.start()
    }

    private fun updateResetTimeView(seconds: Int) {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val time = String.format(Locale.getDefault(), "%02d小时%02d分钟", hours, minutes)
        EventBus.getDefault().post(ApplicationEvent.UpdateResetTickTime("${time}后刷新每日任务"))
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
        taskTimer?.cancel()
        taskTimer = null

        EventBus.getDefault().unregister(this)
        unregisterReceiver(systemBroadcastReceiver)

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}