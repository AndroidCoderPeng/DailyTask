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
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Calendar
import java.util.Locale

/**
 * APP前台服务，降低APP被系统杀死的可能性
 * */
class ForegroundRunningService : Service(), CoroutineScope by MainScope() {

    @Volatile
    private var isTaskReset = false

    @Volatile
    private var isTimerRunning = false
    private var taskTimer: CountDownTimer? = null

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
                setContentText("为保证程序正常运行，请勿移除此通知")
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
        startForeground(Constant.FOREGROUND_RUNNING_SERVICE_NOTIFICATION_ID, notification)

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private val systemBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let {
                // 监听时间，系统级广播，每分钟触发一次。
                if (it == Intent.ACTION_TIME_TICK) {
                    val hour = SaveKeyValues.getValue(
                        Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
                    ) as Int
                    if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) == hour) {
                        resetTask()
                    }
                }
            }
        }
    }

    private fun resetTask() {
        if (!isTaskReset) {
            val autoStart = SaveKeyValues.getValue(Constant.TASK_AUTO_START_KEY, true) as Boolean
            if (autoStart) {
                EventBus.getDefault().post(ApplicationEvent.ResetDailyTask)
            }

            isTaskReset = true

            // 重置任务计时器
            val hour = SaveKeyValues.getValue(
                Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
            ) as Int
            startResetTaskTimer(hour)
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
        taskTimer = null

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

        cancel()

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}