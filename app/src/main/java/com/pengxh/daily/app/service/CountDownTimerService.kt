package com.pengxh.daily.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Binder
import android.os.CountDownTimer
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DailyTaskController
import com.pengxh.daily.app.utils.LogFileManager

/**
 * APP倒计时服务，解决手机灭屏后倒计时会出现延迟的问题
 * */
class CountDownTimerService : Service() {

    companion object {
        private const val ACTION_START_DAILY_TASK =
            "com.pengxh.daily.app.action.START_DAILY_TASK"
        private const val ACTION_STOP_DAILY_TASK =
            "com.pengxh.daily.app.action.STOP_DAILY_TASK"

        fun startDailyTask(context: Context) {
            startServiceAction(context, ACTION_START_DAILY_TASK)
        }

        fun stopDailyTask(context: Context) {
            startServiceAction(context, ACTION_STOP_DAILY_TASK)
        }

        fun startService(context: Context) {
            startServiceAction(context, null)
        }

        private fun startServiceAction(context: Context, action: String?) {
            val intent = Intent(context, CountDownTimerService::class.java).apply {
                this.action = action
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val kTag = "CountDownTimerService"
    private val binder by lazy { LocaleBinder() }
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
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

    inner class LocaleBinder : Binder() {
        fun getService(): CountDownTimerService = this@CountDownTimerService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

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
        DailyTaskController.attachCountDownTimerService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DailyTaskController.attachCountDownTimerService(this)
        when (intent?.action) {
            ACTION_START_DAILY_TASK -> DailyTaskController.startTask(this)
            ACTION_STOP_DAILY_TASK -> DailyTaskController.stopTask()
            else -> {
                if (DailyTaskController.isTaskStarted()) {
                    DailyTaskController.startTask(this)
                }
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
            if (seconds <= 0) {
                isTimerRunning = false
                currentTaskIndex = -1
                val notification = notificationBuilder.apply {
                    setContentText("正在执行第${taskIndex}个任务")
                }.build()
                notificationManager.notify(
                    Constant.COUNTDOWN_TIMER_SERVICE_NOTIFICATION_ID,
                    notification
                )
                DailyTaskController.openTargetApplication(this, trackTaskResult = true)
                return@synchronized
            }

            countDownTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    val seconds = (millisUntilFinished / 1000).toInt()
                    val notification = notificationBuilder.apply {
                        setContentText("${seconds.formatTime()}后执行第${taskIndex}个任务")
                    }.build()
                    notificationManager.notify(
                        Constant.COUNTDOWN_TIMER_SERVICE_NOTIFICATION_ID,
                        notification
                    )
                }

                override fun onFinish() {
                    synchronized(timerLock) {
                        isTimerRunning = false
                        currentTaskIndex = -1
                    }
                    DailyTaskController.openTargetApplication(
                        this@CountDownTimerService,
                        trackTaskResult = true
                    )
                }
            }.apply {
                start()
            }
            isTimerRunning = true
        }
    }

    fun updateDailyTaskState() {
        val notification = notificationBuilder.apply {
            setContentText("当天所有任务已执行完毕")
        }.build()
        notificationManager.notify(Constant.COUNTDOWN_TIMER_SERVICE_NOTIFICATION_ID, notification)
        isTimerRunning = false
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
        DailyTaskController.detachCountDownTimerService(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(kTag, "onDestroy: CountDownTimerService")
    }
}
