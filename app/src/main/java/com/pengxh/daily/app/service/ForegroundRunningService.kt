package com.pengxh.daily.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * APP 前台服务，降低 APP 被系统杀死的可能性。
 * 同时托管 TaskScheduler 的协程作用域。
 */
class ForegroundRunningService : Service() {

    companion object {
        private val _notificationText = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val notificationText = _notificationText.asSharedFlow()

        /**
         * 更新通知文字
         * */
        fun emitNotificationText(text: String) {
            _notificationText.tryEmit(text)
        }

        private val _resetTickTime = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)
        val resetTickTime = _resetTickTime.asSharedFlow()

        /**
         * 更新任务重置时间倒计时文字
         * */
        fun emitResetTickTime(text: String) {
            _resetTickTime.tryEmit(text)
        }

        private val _resetTaskTime = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val resetTaskTime = _resetTaskTime.asSharedFlow()

        /**
         * 更新任务重置时间点
         * */
        fun emitResetTaskTime() {
            _resetTaskTime.tryEmit(Unit)
        }
    }

    private val batteryManager by lazy { getSystemService(BatteryManager::class.java) }
    private var lastRemindTime = 0L
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private lateinit var notificationBuilder: NotificationCompat.Builder

    override fun onCreate() {
        super.onCreate()
        // 注入协程作用域给 TaskScheduler
        TaskScheduler.attach(serviceScope)

        val name = "${resources.getString(R.string.app_name)}前台服务"
        val channel = NotificationChannel(
            "foreground_running_service_channel", name, NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Channel for Foreground Running Service"
        }
        notificationManager.createNotificationChannel(channel)
        notificationBuilder =
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Constant.FOREGROUND_RUNNING_SERVICE_NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(Constant.FOREGROUND_RUNNING_SERVICE_NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            notificationText.collect { text ->
                val notification = notificationBuilder.apply {
                    setContentText(text)
                }.build()
                notificationManager.notify(
                    Constant.FOREGROUND_RUNNING_SERVICE_NOTIFICATION_ID, notification
                )
            }
        }

        serviceScope.launch {
            resetTaskTime.collect {
                updateResetTimeView()
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK) // 每分钟广播
            addAction(Intent.ACTION_BATTERY_CHANGED) // 电池状态改变广播
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemBroadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(systemBroadcastReceiver, filter)
        }

        // 立即更新一次倒计时显示
        updateResetTimeView()

        // 检查电量
        checkLowBattery()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        checkLowBattery()
        return START_STICKY
    }

    private val systemBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let {
                when (it) {
                    Intent.ACTION_TIME_TICK -> {
                        updateResetTimeView()
                        checkAndTriggerReset()
                    }

                    Intent.ACTION_BATTERY_CHANGED -> checkLowBattery()
                }
            }
        }
    }

    private fun updateResetTimeView() {
        val resetHour = SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        val seconds = resetTaskSeconds(resetHour)

        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val time = String.format(Locale.getDefault(), "%02d小时%02d分钟", hours, minutes)
        emitResetTickTime("${time}后刷新每日任务")
    }

    private fun resetTaskSeconds(hour: Int): Int {
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)
        val currentSecond = calendar.get(Calendar.SECOND)

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
        } else if (currentHour == hour && currentMinute == 0 && currentSecond == 0) {
            // 刚好是整点，计算明天的
            todayTargetMillis.add(Calendar.DATE, 1)
            todayTargetMillis.timeInMillis
        } else {
            // 今天已经过了计划时间，计算明天的
            todayTargetMillis.add(Calendar.DATE, 1)
            todayTargetMillis.timeInMillis
        }

        val delta = (targetMillis - System.currentTimeMillis()) / 1000
        return delta.toInt()
    }

    private fun checkLowBattery() {
        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (battery < 20) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastRemindTime < 5 * 60 * 1000) {
                return
            }

            MessageDispatcher.sendMessage("低电量提醒", "手机电量低于20%，请及时充电")
            lastRemindTime = currentTime
        } else {
            // 电量恢复到20%以上，重置提醒时间
            lastRemindTime = 0L
        }
    }

    /**
     * 每分钟检查是否需要触发任务重置
     * 作为协程 delay 的兜底，防止长时间运行后协程异常退出导致任务不重置
     */
    private fun checkAndTriggerReset() {
        val resetHour = SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        // 只在 resetHour ~ resetHour+1 这个范围触发检查
        if (currentHour !in resetHour..(resetHour + 1)) {
            return
        }

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val lastResetDate = SaveKeyValues.loadString(Constant.LAST_RESET_DATE_KEY, "")

        // 今天已重置，跳过
        if (lastResetDate == today) {
            return
        }

        // 标记今天已重置，防止重复触发
        SaveKeyValues.saveString(Constant.LAST_RESET_DATE_KEY, today)

        // 任务重置
        if (SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)) {
            TaskScheduler.startTask()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(systemBroadcastReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 还原通知文本
        val notification = notificationBuilder.apply {
            setContentText("为保证程序正常运行，请勿移除此通知")
        }.build()
        notificationManager.notify(
            Constant.FOREGROUND_RUNNING_SERVICE_NOTIFICATION_ID, notification
        )

        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
