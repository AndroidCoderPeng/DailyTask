package com.pengxh.daily.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.CountDownTimer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.utils.LogFileManager

/**
 * APP倒计时服务，解决手机灭屏后倒计时会出现延迟的问题
 * */
class CountDownTimerService : Service() {
    private val binder by lazy { LocaleBinder() }
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, "countdown_timer_service_channel").apply {
            setSmallIcon(R.mipmap.ic_launcher)
            setContentText("倒计时服务已就绪")
            setPriority(NotificationCompat.PRIORITY_MIN)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setSilent(true)
            setCategory(NotificationCompat.CATEGORY_SERVICE)
            setShowWhen(true)
            setSound(null)
            setVibrate(null)
        }
    }
    private val notificationId = 1001
    private var countDownTimer: CountDownTimer? = null
    private var isTimerRunning = false

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
            "countdown_timer_service_channel", name, NotificationManager.IMPORTANCE_HIGH
        )
        channel.description = "Channel for CountDownTimer Service"
        notificationManager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(notificationId, notificationBuilder.build())
        return START_STICKY
    }

    fun startCountDown(taskIndex: Int, seconds: Int) {
        if (isTimerRunning) {
            countDownTimer?.cancel()
            countDownTimer = null
            isTimerRunning = false
            LogFileManager.writeLog("startCountDown: 第${taskIndex}个任务重复执行，取消之前的任务")
        }
        LogFileManager.writeLog("startCountDown: 倒计时任务开始，执行第${taskIndex}个任务")
        countDownTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                val notification = notificationBuilder.apply {
                    setContentText("${seconds.formatTime()}后执行第${taskIndex}个任务")
                }.build()
                notificationManager.notify(notificationId, notification)
            }

            override fun onFinish() {
                isTimerRunning = false
                openApplication(true)
            }
        }.apply {
            start()
        }
        isTimerRunning = true
    }

    fun updateDailyTaskState() {
        val notification = notificationBuilder.apply {
            setContentText("当天所有任务已执行完毕")
        }.build()
        notificationManager.notify(notificationId, notification)
        isTimerRunning = false
    }

    fun cancelCountDown() {
        if (isTimerRunning) {
            countDownTimer?.cancel()
            countDownTimer = null
            val notification = notificationBuilder.apply {
                setContentText("倒计时任务已停止")
            }.build()
            notificationManager.notify(notificationId, notification)
            isTimerRunning = false
        }
        LogFileManager.writeLog("cancelCountDown: 倒计时任务取消")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        cancelCountDown()
    }
}