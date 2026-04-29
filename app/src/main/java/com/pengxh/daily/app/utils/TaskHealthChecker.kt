package com.pengxh.daily.app.utils

import android.app.AlarmManager
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import android.content.ComponentName
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.kt.lite.utils.SaveKeyValues

object TaskHealthChecker {

    data class CheckResult(
        val blockers: List<String>,
        val warnings: List<String>
    ) {
        fun canContinue(): Boolean = blockers.isEmpty()
    }

    fun checkBeforeExecution(
        context: Context,
        trackTaskResult: Boolean,
        remoteScreenshot: Boolean,
        allowKeyguardDismissAttempt: Boolean = false
    ): CheckResult {
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val appContext = context.applicationContext
        val resultSource = SaveKeyValues.getValue(Constant.RESULT_SOURCE_KEY, 0) as Int

        val keyguardManager = appContext.getSystemService(KeyguardManager::class.java)
        if (keyguardManager?.isKeyguardLocked == true) {
            if (allowKeyguardDismissAttempt && canAttemptDismissKeyguard(appContext)) {
                warnings += "设备处于无安全密码锁屏状态，执行时会先尝试唤醒解锁"
            } else {
                blockers += "设备处于系统锁屏状态，无法保证目标应用可被打开"
            }
        }

        val needsNotificationResult = trackTaskResult && !remoteScreenshot && resultSource == 0
        if (needsNotificationResult && !isNotificationListenerReady(appContext)) {
            blockers += "通知监听未授权或已断开，无法接收打卡成功通知"
        }

        val needsScreenshotResult = remoteScreenshot || (trackTaskResult && resultSource == 1)
        if (needsScreenshotResult && ProjectionSession.state != ProjectionSession.State.ACTIVE) {
            blockers += "截图服务未授权或已断开，无法获取执行结果截图"
        }

        if (!Settings.canDrawOverlays(appContext)) {
            warnings += "悬浮窗权限未开启，倒计时和息屏蒙层可能不可见"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = appContext.getSystemService(AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {
                warnings += "精确闹钟权限未开启，每日重置会降级为非精确 Alarm"
            }
        }

        val powerManager = appContext.getSystemService(PowerManager::class.java)
        if (!powerManager.isIgnoringBatteryOptimizations(appContext.packageName)) {
            warnings += "电池优化未关闭，后台服务可能被系统限制"
        }

        return CheckResult(blockers, warnings)
    }

    fun canAttemptDismissKeyguard(context: Context): Boolean {
        val keyguardManager = context.applicationContext.getSystemService(KeyguardManager::class.java)
            ?: return false
        return keyguardManager.isKeyguardLocked &&
            !keyguardManager.isDeviceSecure &&
            !keyguardManager.isKeyguardSecure
    }

    fun isKeyguardLocked(context: Context): Boolean {
        val keyguardManager = context.applicationContext.getSystemService(KeyguardManager::class.java)
            ?: return false
        return keyguardManager.isKeyguardLocked
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val packages = NotificationManagerCompat.getEnabledListenerPackages(context)
        return packages.contains(context.packageName)
    }

    fun isNotificationListenerReady(context: Context): Boolean {
        val enabled = isNotificationListenerEnabled(context)
        val connected = NotificationListenerState.isConnected()
        if (enabled && !connected) {
            NotificationListenerService.requestRebind(
                ComponentName(context, NotificationMonitorService::class.java)
            )
        }
        return enabled && connected
    }

    fun formatBlockers(blockers: List<String>): String {
        return buildString {
            appendLine("无人值守自检失败，任务已停止：")
            blockers.forEachIndexed { index, item ->
                appendLine("${index + 1}. $item")
            }
        }.trim()
    }

    fun formatWarnings(warnings: List<String>): String {
        return buildString {
            appendLine("无人值守自检提醒：")
            warnings.forEachIndexed { index, item ->
                appendLine("${index + 1}. $item")
            }
        }.trim()
    }
}
