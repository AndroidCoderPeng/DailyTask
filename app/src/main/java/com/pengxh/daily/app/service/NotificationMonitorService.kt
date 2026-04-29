package com.pengxh.daily.app.service

import android.app.Notification
import android.content.ComponentName
import android.os.BatteryManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DailyTaskController
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.HttpRequestManager
import com.pengxh.daily.app.utils.NotificationListenerState
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.utils.TaskHealthChecker
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.timestampToCompleteDate
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus

/**
 * @description: 状态栏监听服务
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/25 23:17
 */
class NotificationMonitorService : NotificationListenerService() {

    private val kTag = "MonitorService"
    private val httpRequestManager by lazy { HttpRequestManager(this) }
    private val emailManager by lazy { EmailManager(this) }
    private val auxiliaryApp = arrayOf(Constant.WECHAT, Constant.QQ, Constant.TIM, Constant.ZFB)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 有可用的并且和通知管理器连接成功时回调
     */
    override fun onListenerConnected() {
        NotificationListenerState.markConnected()
        EventBus.getDefault().post(ApplicationEvent.ListenerConnected)
    }

    /**
     * 当有新通知到来时会回调
     */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationListenerState.markConnected()

        val extras = sbn.notification.extras
        val pkg = sbn.packageName
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val notice = extras.getString(Notification.EXTRA_TEXT)
            ?: extras.getString(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString("\n")
            ?: extras.getString(Notification.EXTRA_SUMMARY_TEXT)

        if (notice.isNullOrBlank()) {
            return
        }

        val targetApp = Constant.getTargetApp()

        // 保存指定包名的通知，其他的一律不保存
        saveTargetNotice(pkg, targetApp, title, notice)

        // 目标应用打卡通知
        val resultSource = SaveKeyValues.getValue(Constant.RESULT_SOURCE_KEY, 0) as Int
        if (resultSource == 0) {
            if (pkg == targetApp && notice.contains("成功")) {
                if (!DailyTaskController.handleTaskSuccess(this, sbn.postTime)) {
                    return
                }
                "即将发送通知邮件，请注意查收".show(this)
                val messageTitle =
                    SaveKeyValues.getValue(Constant.MESSAGE_TITLE_KEY, "打卡结果通知") as String
                sendChannelMessage(title.ifBlank { messageTitle }, notice)
            }
        }

        // 其他消息指令
        handleRemoteCommand(pkg, notice)
    }

    private fun saveTargetNotice(pkg: String, targetApp: String, title: String, notice: String) {
        if (pkg != targetApp && pkg !in auxiliaryApp) return

        NotificationBean().apply {
            packageName = pkg
            noticeTitle = title
            noticeMessage = notice
            postTime = System.currentTimeMillis().timestampToCompleteDate()
        }.also {
            serviceScope.launch {
                try {
                    DatabaseWrapper.insertNotice(it)
                } catch (e: Exception) {
                    Log.e(kTag, "Insert notice failed", e)
                }
            }
        }
    }

    private fun handleRemoteCommand(pkg: String, notice: String) {
        if (pkg in auxiliaryApp) {
            when {
                notice.contains("执行任务") -> {
                    CountDownTimerService.startDailyTask(this)
                }

                notice.contains("终止任务") -> {
                    CountDownTimerService.stopDailyTask(this)
                }

                notice.contains("开启循环") -> {
                    SaveKeyValues.putValue(Constant.TASK_AUTO_START_KEY, true)
                    sendChannelMessage("循环任务状态通知", "循环任务状态已更新为：开启")
                }

                notice.contains("关闭循环") -> {
                    SaveKeyValues.putValue(Constant.TASK_AUTO_START_KEY, false)
                    sendChannelMessage("循环任务状态通知", "循环任务状态已更新为：关闭")
                }

                notice.contains("息屏") -> {
                    DailyTaskController.setMaskVisible(this, true)
                }

                notice.contains("亮屏") -> {
                    DailyTaskController.setMaskVisible(this, false)
                }

                notice.contains("考勤记录") -> {
                    serviceScope.launch {
                        val notices = try {
                            DatabaseWrapper.loadCurrentDayNotice()
                        } catch (e: Exception) {
                            Log.e(kTag, "Load notices failed", e)
                            emptyList()
                        }

                        val record = buildString {
                            var index = 1
                            notices.filter {
                                it.noticeMessage.contains("考勤打卡")
                            }.forEach {
                                append("【第${index}次】${it.noticeMessage}，时间：${it.postTime}\r\n")
                                index++
                            }
                        }

                        withContext(Dispatchers.Main) {
                            sendChannelMessage("当天考勤记录通知", record)
                        }
                    }
                }

                notice.contains("状态查询") -> {
                    val type = SaveKeyValues.getValue(Constant.CHANNEL_TYPE_KEY, -1) as Int
                    val health = TaskHealthChecker.checkBeforeExecution(
                        this,
                        trackTaskResult = true,
                        remoteScreenshot = false,
                        allowKeyguardDismissAttempt = true
                    )
                    val healthItems = health.blockers + health.warnings
                    val powerSaveMode =
                        SaveKeyValues.getValue(Constant.POWER_SAVE_MODE_KEY, false) as Boolean
                    val lowBatteryReminder =
                        SaveKeyValues.getValue(Constant.LOW_BATTERY_REMINDER_KEY, true) as Boolean
                    val battery = getSystemService(BatteryManager::class.java)
                        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val content = buildString {
                        appendLine("任务状态：${if (DailyTaskController.isTaskStarted()) "运行中" else "已停止"}")
                        appendLine("省电模式：${if (powerSaveMode) "开启" else "关闭"}")
                        appendLine(
                            "低电量提醒：${if (lowBatteryReminder) "开启" else "关闭"}" +
                                "（阈值${Constant.DEFAULT_LOW_BATTERY_THRESHOLD}%，当前${if (battery >= 0) "$battery%" else "未知"}）"
                        )
                        appendLine("悬浮权限：${if (Settings.canDrawOverlays(this@NotificationMonitorService)) "已获取" else "被拒绝"}")
                        appendLine("通知监听：${if (TaskHealthChecker.isNotificationListenerReady(this@NotificationMonitorService)) "正常" else "断开"}")
                        appendLine("截图服务：${if (ProjectionSession.state == ProjectionSession.State.ACTIVE) "正常" else "断开"}")
                        appendLine("无人值守自检：${if (healthItems.isEmpty()) "正常" else healthItems.joinToString("；")}")
                        append("消息渠道：${if (type == 0) "企业微信" else "QQ邮箱"}")
                    }
                    sendChannelMessage("状态查询通知", content)
                }

                notice.contains("截屏") -> {
                    if (ProjectionSession.state == ProjectionSession.State.ACTIVE) {
                        DailyTaskController.openTargetApplication(
                            this,
                            trackTaskResult = false,
                            remoteScreenshot = true
                        )
                    } else {
                        sendChannelMessage("截屏状态通知", "截屏服务已断开，截屏失败")
                    }
                }

                else -> {
                    val key = SaveKeyValues.getValue(Constant.TASK_COMMAND_KEY, "打卡") as String
                    if (key.isNotBlank() && notice.contains(key)) {
                        if (DailyTaskController.isExecutionWindowActive()) {
                            sendChannelMessage(
                                "远程执行状态通知",
                                "已有任务正在执行或准备执行，请稍后再发送手动打卡口令"
                            )
                        } else {
                            DailyTaskController.openTargetApplication(
                                this,
                                trackTaskResult = true,
                                advanceSchedulerOnResult = false
                            )
                        }
                    }
                }
            }
        }
    }

    private fun sendChannelMessage(title: String, content: String) {
        val type = SaveKeyValues.getValue(Constant.CHANNEL_TYPE_KEY, -1) as Int
        when (type) {
            0 -> {
                // 企业微信
                httpRequestManager.sendMessage(title, content)
            }

            1 -> {
                // QQ邮箱
                emailManager.sendEmail(title, content, false)
            }

            else -> {
                Log.d(kTag, "sendChannelMessage: 消息渠道不支持")
            }
        }
    }

    /**
     * 当有通知移除时会回调
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    override fun onListenerDisconnected() {
        val wasConnected = NotificationListenerState.markDisconnected()
        EventBus.getDefault().post(ApplicationEvent.ListenerDisconnected)
        if (wasConnected) {
            sendChannelMessage(
                "无人值守异常通知",
                "通知监听服务已断开，系统已请求重新绑定；恢复前可能无法识别目标应用的打卡成功通知"
            )
        }
        // 主动请求系统重新绑定监听服务
        requestRebind(ComponentName(this, NotificationMonitorService::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        NotificationListenerState.markDisconnected()
        serviceScope.cancel()
    }
}
