package com.pengxh.daily.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.utils.AlarmScheduler
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.ChinaHolidayRemoteUpdater
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

    private val batteryManager by lazy { getSystemService(BatteryManager::class.java) }
    private val httpRequestManager by lazy { HttpRequestManager(this) }
    private val emailManager by lazy { EmailManager(this) }
    private var lastRemindTime = 0L

    override fun onCreate() {
        super.onCreate()
        EventBus.getDefault().register(this)

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

        // 每次 Service 启动时重新注册 Alarm
        val resetHour = SaveKeyValues.getValue(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        ) as Int
        AlarmScheduler.schedule(this, resetHour)

        ChinaHolidayRemoteUpdater.refreshIfNeeded(this)

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
                    Intent.ACTION_TIME_TICK -> updateResetTimeView()

                    Intent.ACTION_BATTERY_CHANGED -> checkLowBattery()
                }
            }
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun handleApplicationEvent(event: ApplicationEvent) {
        if (event is ApplicationEvent.SetResetTaskTime) {
            // 重新计算并更新倒计时显示
            updateResetTimeView()
        }
    }

    private fun updateResetTimeView() {
        val resetHour = SaveKeyValues.getValue(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        ) as Int
        val seconds = resetTaskSeconds(resetHour)

        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val time = String.format(Locale.getDefault(), "%02d小时%02d分钟", hours, minutes)
        EventBus.getDefault().post(ApplicationEvent.UpdateResetTickTime("${time}后刷新每日任务"))
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

            when (SaveKeyValues.getValue(Constant.CHANNEL_TYPE_KEY, 0) as Int) {
                0 -> httpRequestManager.sendMessage("低电量提醒", "")
                1 -> emailManager.sendEmail("低电量提醒", "", false)
                else -> LogFileManager.writeLog("低电量提醒未发送，消息渠道未配置，当前电量：$battery%")
            }
            lastRemindTime = currentTime
        } else {
            // 电量恢复到20%以上，重置提醒时间
            lastRemindTime = 0L
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(systemBroadcastReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        EventBus.getDefault().unregister(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}