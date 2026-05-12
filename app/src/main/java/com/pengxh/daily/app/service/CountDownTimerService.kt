package com.pengxh.daily.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.CountDownTimer
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.kt.lite.utils.SaveKeyValues

/**
 * APP倒计时服务，解决手机灭屏后倒计时会出现延迟的问题
 * */
class CountDownTimerService : Service() {

    companion object {
        const val ACTION_START_COUNTDOWN = "com.pengxh.daily.app.START_COUNTDOWN"
        const val ACTION_CANCEL_COUNTDOWN = "com.pengxh.daily.app.CANCEL_COUNTDOWN"
        const val ACTION_COMPLETED_DAILY_TASK = "com.pengxh.daily.app.ACTION_COMPLETED_DAILY_TASK"
        const val EXTRA_TASK_INDEX = "extra_task_index"
        const val EXTRA_SECONDS = "extra_seconds"
    }

    private val kTag = "CountDownTimerService"
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, "countdown_timer_service_channel").apply {
            setSmallIcon(R.mipmap.ic_launcher)
            setContentText("倒计时服务已就绪")
            setPriority(NotificationCompat.PRIORITY_LOW)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setSilent(true)
            setCategory(NotificationCompat.CATEGORY_SERVICE)
            setShowWhen(true)
            setSound(null)
            setVibrate(null)
        }
    }
    private val timerLock = Any()
    private var countDownTimer: CountDownTimer? = null

    @Volatile
    private var isTimerRunning = false
    private var currentTaskIndex: Int = -1

    override fun onCreate() {
        super.onCreate()
        val name = "${resources.getString(R.string.app_name)}倒计时服务"
        val channel = NotificationChannel(
            "countdown_timer_service_channel", name, NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Channel for CountDownTimer Service"
        }
        notificationManager.createNotificationChannel(channel)
        val notification = notificationBuilder.build()
        startForeground(Constant.COUNTDOWN_TIMER_SERVICE_NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_COUNTDOWN -> {
                val taskIndex = intent.getIntExtra(EXTRA_TASK_INDEX, -1)
                val seconds = intent.getIntExtra(EXTRA_SECONDS, 0)
                if (taskIndex != -1 && seconds > 0) {
                    startCountDown(taskIndex, seconds)
                }
            }

            ACTION_CANCEL_COUNTDOWN -> cancelCountDown()

            ACTION_COMPLETED_DAILY_TASK -> {
                val notification = notificationBuilder.apply {
                    setContentText("当天所有任务已执行完毕")
                }.build()
                notificationManager.notify(
                    Constant.COUNTDOWN_TIMER_SERVICE_NOTIFICATION_ID, notification
                )
                isTimerRunning = false
            }
        }
        return START_STICKY
    }

    fun startCountDown(taskIndex: Int, seconds: Int) {
        synchronized(timerLock) {
            // 如果是同一个任务正在执行，直接跳过
            if (isTimerRunning && currentTaskIndex == taskIndex) {
                LogFileManager.writeLog("startCountDown: 任务$taskIndex 已在执行中，跳过")
                return@synchronized
            }

            // 如果有其他任务正在执行，先取消它
            if (isTimerRunning) {
                countDownTimer?.cancel()
                countDownTimer = null
                isTimerRunning = false
                LogFileManager.writeLog("startCountDown: 取消之前的任务（任务${currentTaskIndex}），准备执行任务$taskIndex")
            }

            currentTaskIndex = taskIndex
            LogFileManager.writeLog("startCountDown: 倒计时任务开始，执行第${taskIndex}个任务")

            val tickInterval = seconds.getCountDownTickInterval()
            countDownTimer = object : CountDownTimer(seconds * 1000L, tickInterval) {
                override fun onTick(millisUntilFinished: Long) {
                    val seconds = (millisUntilFinished / 1000).toInt()
                    val notification = notificationBuilder.apply {
                        setContentText("${seconds.formatTime()}后执行第${taskIndex}个任务")
                    }.build()
                    notificationManager.notify(
                        Constant.COUNTDOWN_TIMER_SERVICE_NOTIFICATION_ID, notification
                    )
                }

                override fun onFinish() {
                    synchronized(timerLock) {
                        isTimerRunning = false
                        currentTaskIndex = -1
                    }
                    openApplication(true)
                }
            }.apply {
                start()
            }
            isTimerRunning = true
        }
    }

    fun cancelCountDown() {
        synchronized(timerLock) {
            if (isTimerRunning) {
                countDownTimer?.cancel()
                countDownTimer = null
                val notification = notificationBuilder.apply {
                    setContentText("倒计时任务已停止")
                }.build()
                notificationManager.notify(
                    Constant.COUNTDOWN_TIMER_SERVICE_NOTIFICATION_ID,
                    notification
                )
                isTimerRunning = false
                currentTaskIndex = -1
            }
            LogFileManager.writeLog("cancelCountDown: 倒计时任务取消")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelCountDown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(kTag, "onDestroy: CountDownTimerService")
    }

    private fun Int.getCountDownTickInterval(): Long {
        val mode = SaveKeyValues.getValue(Constant.POWER_SAVE_MODE_KEY, false) as Boolean
        return if (mode && this > 60) {
            60_000L
        } else {
            1_000L
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}