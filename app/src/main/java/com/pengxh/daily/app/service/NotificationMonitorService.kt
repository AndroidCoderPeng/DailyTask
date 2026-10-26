package com.pengxh.daily.app.service

import android.app.Notification
import android.content.ComponentName
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.MonitorEvent
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.timestampToCompleteDate
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * @description: 状态栏监听服务
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/25 23:17
 */
class NotificationMonitorService : NotificationListenerService() {
    companion object {
        private val eventChannel = Channel<MonitorEvent>(Channel.UNLIMITED)
        val events = eventChannel.receiveAsFlow()

        /**
         * 发送事件
         */
        fun emitMonitorEvent(event: MonitorEvent) {
            val result = eventChannel.trySend(event)
            if (result.isFailure) {
                Log.e("MonitorService", "Failed to queue monitor event", result.exceptionOrNull())
            }
        }

        private val _listenerState = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 1)
        val listenerState = _listenerState.asSharedFlow()

        /**
         * 发送监听状态
         */
        fun emitListenerState(connected: Boolean) {
            _listenerState.tryEmit(connected)
        }
    }

    private val kTag = "MonitorService"
    private val auxiliaryApp = arrayOf(Constant.WECHAT, Constant.QQ, Constant.TIM, Constant.ZFB)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenerConnected = false
    private val noticeCleanupLock = Any()
    private var lastNoticeCleanupDate: LocalDate? = null

    /**
     * 有可用的并且和通知管理器连接成功时回调
     */
    override fun onListenerConnected() {
        listenerConnected = true
        emitListenerState(true)
        cleanupOldNoticesIfNeeded()
    }

    /**
     * 当有新通知到来时会回调
     */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
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
        cleanupOldNoticesIfNeeded()
        val targetApp = Constant.getTargetApp()

        // 保存指定包名的通知，其他的一律不保存
        saveTargetNotice(pkg, targetApp, title, notice)

        // 结果来源=通知监听时，仅钉钉支持该判定通道（其他应用只能走截屏服务）
        if (SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX) == 0 &&
            targetApp == Constant.DING_DING
        ) {
            if (pkg == targetApp && notice.contains("成功")) {
                TaskScheduler.notifyClockIn(sbn.postTime)
                emitMonitorEvent(MonitorEvent.ClockInSuccess)
                "即将发送通知邮件，请注意查收".show(this)
                val messageTitle =
                    SaveKeyValues.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")
                MessageDispatcher.sendMessage(title.ifBlank { messageTitle }, notice)
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

    private fun cleanupOldNoticesIfNeeded() {
        val today = LocalDate.now()
        synchronized(noticeCleanupLock) {
            if (lastNoticeCleanupDate == today) return
            lastNoticeCleanupDate = today
        }

        val cutoff = today.minusDays(90).toString()
        serviceScope.launch {
            try {
                val deletedCount = DatabaseWrapper.deleteNoticesBefore(cutoff)
                if (deletedCount > 0) {
                    Log.d(kTag, "Deleted $deletedCount notification records older than $cutoff")
                }
            } catch (e: Exception) {
                Log.e(kTag, "Failed to clean up old notification records", e)
                synchronized(noticeCleanupLock) {
                    if (lastNoticeCleanupDate == today) {
                        lastNoticeCleanupDate = null
                    }
                }
            }
        }
    }

    /**
     * 处理远程指令
     */
    private fun handleRemoteCommand(pkg: String, notice: String) {
        if (pkg !in auxiliaryApp) return

        Log.d(kTag, "handleRemoteCommand: notice = $notice")
        val prefixIndex = notice.indexOf(Constant.COMMAND_PREFIX)
        if (prefixIndex < 0) {
            Log.d(kTag, "handleRemoteCommand: 未检测到 DT# 指令前缀")
            return
        }

        // 折叠通知格式形如 "[n条]昵称: DT#执行任务"，截取 DT# 之后的真正指令
        val command = notice.substring(prefixIndex).trim()
        Log.d(kTag, "handleRemoteCommand: command = $command")

        val key = SaveKeyValues.loadString(Constant.REMOTE_COMMAND_KEY, "打卡").trim()
        if (key.isNotEmpty() && command == "${Constant.COMMAND_PREFIX}$key") {
            val opened = triggerRemoteClockIn()
            sendCommandReceipt(
                "打卡指令通知",
                if (opened) "远程打卡指令已接收" else "无法打开目标应用，远程打卡未执行"
            )
            return
        }

        when {
            command.contains("执行任务") -> {
                val message = when {
                    TaskScheduler.isRunning() -> "任务已在运行中"
                    TaskScheduler.startTask() -> "任务启动指令已接收"
                    else -> "任务启动失败，请检查前台服务状态"
                }
                sendCommandReceipt("任务指令通知", message)
            }

            command.contains("终止任务") -> {
                if (TaskScheduler.isRunning()) {
                    TaskScheduler.stopTask()
                    sendCommandReceipt("停止任务通知", "任务停止成功，请及时打开下次任务")
                } else {
                    sendCommandReceipt("停止任务通知", "当前没有正在运行的任务")
                }
            }

            command.contains("开启循环") -> {
                SaveKeyValues.saveBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
                sendCommandReceipt("循环任务状态通知", "循环任务状态已更新为：开启")
            }

            command.contains("关闭循环") -> {
                SaveKeyValues.saveBoolean(Constant.TASK_AUTO_RECYCLE_KEY, false)
                sendCommandReceipt("循环任务状态通知", "循环任务状态已更新为：关闭")
            }

            command.contains("息屏") -> {
                SaveKeyValues.saveInt(Constant.REMOTE_MASK_MODE_KEY, 1)
                emitMonitorEvent(MonitorEvent.ShowMaskCommand)
                sendCommandReceipt("息屏指令通知", "息屏状态已记录，主界面可用时生效")
            }

            command.contains("亮屏") -> {
                SaveKeyValues.saveInt(Constant.REMOTE_MASK_MODE_KEY, 0)
                emitMonitorEvent(MonitorEvent.HideMaskCommand)
                sendCommandReceipt("亮屏指令通知", "亮屏状态已记录，主界面可用时生效")
            }

            command.contains("考勤记录") -> {
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

                    val content = record.ifBlank { "当天暂无考勤打卡记录" }
                    withContext(Dispatchers.Main) {
                        sendCommandReceipt("当天考勤记录通知", content)
                    }
                }
            }

            command.contains("状态查询") -> {
                val type = SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
                val content = buildString {
                    appendLine("任务状态：${if (TaskScheduler.isRunning()) "运行中" else "已停止"}")
                    appendLine("悬浮权限：${if (Settings.canDrawOverlays(this@NotificationMonitorService)) "已获取" else "被拒绝"}")
                    appendLine("通知监听：${if (listenerConnected) "正常" else "断开"}")
                    appendLine("截图服务：${if (ProjectionSession.isStateActive()) "正常" else "断开"}")
                    append(
                        "消息渠道：${when (type) {
                            0 -> "QQ邮箱"
                            1 -> "企业微信"
                            else -> "未配置"
                        }}"
                    )
                }
                sendCommandReceipt("状态查询通知", content)
            }

            command.contains("截屏") -> {
                if (ProjectionSession.isStateActive()) {
                    val opened = openTargetAppForScreenshot()
                    sendCommandReceipt(
                        "截屏状态通知",
                        if (opened) "截屏指令已接收，正在处理"
                        else "无法打开目标应用，截屏未执行"
                    )
                } else {
                    sendCommandReceipt("截屏状态通知", "截屏服务已断开，截屏失败")
                }
            }

            else -> Unit
        }
    }

    private fun triggerRemoteClockIn(): Boolean {
        val timeoutSeconds = SaveKeyValues.loadInt(
            Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
        )
        val returnScreenshot = SaveKeyValues.loadBoolean(
            Constant.REMOTE_CLOCK_IN_CAPTURE_KEY, false
        )
        var opened = false
        openApplication {
            opened = true
            serviceScope.launch {
                countdownTargetApp(timeoutSeconds)
                if (returnScreenshot) {
                    captureAndSendResult(
                        "打卡结果通知",
                        "打卡完成，结果见附件",
                        "打卡完成，但是无法获取截图"
                    )
                }
                emitMonitorEvent(MonitorEvent.ReturnToApp)
            }
        }
        return opened
    }

    private fun openTargetAppForScreenshot(): Boolean {
        var opened = false
        openApplication {
            opened = true
            serviceScope.launch {
                countdownTargetApp(10)
                captureAndSendResult(
                    "截屏状态通知",
                    "截图完成",
                    "截图完成，但是无法获取截图"
                )
                emitMonitorEvent(MonitorEvent.ReturnToApp)
            }
        }
        return opened
    }

    private suspend fun countdownTargetApp(countdownSeconds: Int) {
        val target = SystemClock.elapsedRealtime() + countdownSeconds * 1000L
        while (true) {
            val remaining = target - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            FloatingWindowController.updateTime((remaining / 1000).toInt())
            delay(minOf(1_000L, remaining).coerceAtLeast(1))
        }
    }

    private suspend fun captureAndSendResult(
        title: String,
        successMessage: String,
        failureMessage: String
    ) {
        val imagePath = CaptureImageService.requestCaptureScreen().await()
        if (imagePath == null) {
            sendCommandReceipt(title, "截屏请求超时")
        } else if (imagePath.isEmpty()) {
            sendCommandReceipt(title, failureMessage)
        } else {
            MessageDispatcher.sendAttachmentMessage(
                title,
                successMessage,
                imagePath,
                onFailure = { error ->
                    Log.w(kTag, "Failed to send capture result: $error")
                    ForegroundRunningService.emitNotificationText("截屏结果发送失败：$error")
                }
            )
        }
    }

    private fun sendCommandReceipt(title: String, content: String) {
        MessageDispatcher.sendMessage(
            title,
            content,
            onFailure = { error ->
                Log.w(kTag, "Failed to send command receipt: $error")
                ForegroundRunningService.emitNotificationText("指令回执发送失败：$error")
            }
        )
    }

    /**
     * 当有通知移除时会回调
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    override fun onListenerDisconnected() {
        listenerConnected = false
        emitListenerState(false)
        // 主动请求系统重新绑定监听服务
        requestRebind(ComponentName(this, NotificationMonitorService::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
