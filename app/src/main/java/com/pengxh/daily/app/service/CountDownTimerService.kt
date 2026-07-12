package com.pengxh.daily.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.TimeoutTimerManager
import com.pengxh.kt.lite.utils.SaveKeyValues

/**
 * APP倒计时服务。
 * 使用 Handler + SystemClock.elapsedRealtime() 自校准，消除了 CountDownTimer 休眠漂移问题。
 * tickInterval 只决定通知栏刷新频率（省电模式 60s / 普通 1s），
 * 剩余时间始终由 targetElapsedTime - elapsedRealtime() 实时计算，确保精度。
 */
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
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 目标时刻（elapsedRealtime 时间戳），休眠唤醒后自动校准
     * */
    private var targetElapsedTime: Long = 0L

    /**
     * 通知栏刷新间隔：普通模式 1 秒，省电模式 60 秒
     * */
    @Volatile
    private var tickInterval: Long = 1000L

    /**
     * 固定的 Runnable，反复 postDelayed，杜绝每次 new 对象带来的时序隐患
     * */
    private val tickRunnable = Runnable { scheduleNextTick() }

    @Volatile
    private var isTimerRunning = false
    private var currentTaskIndex = -1

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
                // 清理残留 tick 回调，防止已完成后再触发
                synchronized(timerLock) {
                    handler.removeCallbacks(tickRunnable)
                    isTimerRunning = false
                    currentTaskIndex = -1
                }
                val notification = notificationBuilder.apply {
                    setContentText("当天所有任务已执行完毕")
                }.build()
                notificationManager.notify(
                    Constant.COUNTDOWN_TIMER_SERVICE_NOTIFICATION_ID, notification
                )
            }
        }
        return START_STICKY
    }

    private fun startCountDown(taskIndex: Int, seconds: Int) {
        synchronized(timerLock) {
            // 如果是同一个任务正在执行，直接跳过
            if (isTimerRunning && currentTaskIndex == taskIndex) {
                LogFileManager.writeLog("startCountDown: 任务$taskIndex 已在执行中，跳过")
                return@synchronized
            }

            // 如果有其他任务正在执行，先取消它
            if (isTimerRunning) {
                stopTimerInternal()
                LogFileManager.writeLog("startCountDown: 取消之前的任务（任务${currentTaskIndex}），准备执行任务$taskIndex")
            }

            currentTaskIndex = taskIndex
            tickInterval = seconds.getCountDownTickInterval()
            LogFileManager.writeLog("startCountDown: 倒计时任务开始，执行第${taskIndex}个任务，tickInterval=${tickInterval}ms")

            // 以 elapsedRealtime 为基准记录目标时刻——这是自校准的核心
            targetElapsedTime = SystemClock.elapsedRealtime() + seconds * 1000L
            isTimerRunning = true
            scheduleNextTick()
        }
    }

    /**
     * 每次 tick 都基于 [targetElapsedTime] 实时计算剩余时间，
     * 即使设备休眠导致 postDelayed 延迟，唤醒后也能立即校准，不会累积误差。
     *
     * tickInterval 只决定通知栏刷新频率（省电 60s / 普通 1s），
     * 显示的时间永远准确，因为来自 elapsedRealtime() 实时差值。
     */
    private fun scheduleNextTick() {
        val remaining = targetElapsedTime - SystemClock.elapsedRealtime()
        if (remaining <= 0) {
            onCountDownFinished()
            return
        }

        val remainingSeconds = (remaining / 1000).toInt()
        val notification = notificationBuilder.apply {
            setContentText("${remainingSeconds.formatTime()}后执行第${currentTaskIndex}个任务")
        }.build()
        notificationManager.notify(
            Constant.COUNTDOWN_TIMER_SERVICE_NOTIFICATION_ID, notification
        )

        // 下一次 post 延迟不超过 tickInterval，且不超出剩余时间
        val delay = minOf(tickInterval, remaining).coerceAtLeast(1)
        // 二次确认：避免在 postDelayed 之前计时器已被取消
        if (isTimerRunning) {
            handler.postDelayed(tickRunnable, delay)
        }
    }

    private fun onCountDownFinished() {
        synchronized(timerLock) {
            handler.removeCallbacks(tickRunnable)  // 防御性清理
            isTimerRunning = false
            currentTaskIndex = -1
        }
        openApplication {
            TimeoutTimerManager.startTimeoutTimer()
        }
    }

    private fun stopTimerInternal() {
        handler.removeCallbacks(tickRunnable)
        isTimerRunning = false
        currentTaskIndex = -1
    }

    fun cancelCountDown() {
        synchronized(timerLock) {
            if (isTimerRunning) {
                stopTimerInternal()
                val notification = notificationBuilder.apply {
                    setContentText("倒计时任务已停止")
                }.build()
                notificationManager.notify(
                    Constant.COUNTDOWN_TIMER_SERVICE_NOTIFICATION_ID,
                    notification
                )
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
        val mode = SaveKeyValues.loadBoolean(Constant.POWER_SAVE_MODE_KEY, false)
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