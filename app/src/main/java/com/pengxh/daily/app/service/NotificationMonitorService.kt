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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @description: 状态栏监听服务
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/25 23:17
 */
class NotificationMonitorService : NotificationListenerService() {
    companion object {
        private val _events = MutableSharedFlow<MonitorEvent>(extraBufferCapacity = 2)
        val events = _events.asSharedFlow()

        /**
         * 发送事件
         */
        fun emitMonitorEvent(event: MonitorEvent) {
            _events.tryEmit(event)
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

    /**
     * 有可用的并且和通知管理器连接成功时回调
     */
    override fun onListenerConnected() {
        listenerConnected = true
        emitListenerState(true)
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

        val targetApp = Constant.getTargetApp()

        // 保存指定包名的通知，其他的一律不保存
        saveTargetNotice(pkg, targetApp, title, notice)

        // 目标应用打卡通知
        if (SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX) == 0) {
            if (pkg == targetApp && notice.contains("成功")) {
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

    /**
     * 处理远程指令
     */
    private fun handleRemoteCommand(pkg: String, notice: String) {
        if (pkg !in auxiliaryApp) return

        // 必须以 DT# 开头，否则忽略
        if (!notice.startsWith(Constant.COMMAND_PREFIX)) return

        when {
            notice.contains("执行任务") -> emitMonitorEvent(MonitorEvent.StartTaskCommand)

            notice.contains("终止任务") -> emitMonitorEvent(MonitorEvent.StopTaskCommand)

            notice.contains("开启循环") -> {
                SaveKeyValues.saveBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
                MessageDispatcher.sendMessage("循环任务状态通知", "循环任务状态已更新为：开启")
            }

            notice.contains("关闭循环") -> {
                SaveKeyValues.saveBoolean(Constant.TASK_AUTO_RECYCLE_KEY, false)
                MessageDispatcher.sendMessage("循环任务状态通知", "循环任务状态已更新为：关闭")
            }

            notice.contains("息屏") -> emitMonitorEvent(MonitorEvent.ShowMaskCommand)

            notice.contains("亮屏") -> emitMonitorEvent(MonitorEvent.HideMaskCommand)

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
                        MessageDispatcher.sendMessage("当天考勤记录通知", record)
                    }
                }
            }

            notice.contains("状态查询") -> {
                val type = SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
                val content = buildString {
                    appendLine("任务状态：${if (TaskScheduler.isRunning()) "运行中" else "已停止"}")
                    appendLine("悬浮权限：${if (Settings.canDrawOverlays(this@NotificationMonitorService)) "已获取" else "被拒绝"}")
                    appendLine("通知监听：${if (listenerConnected) "正常" else "断开"}")
                    appendLine("截图服务：${if (ProjectionSession.isStateActive()) "正常" else "断开"}")
                    append("消息渠道：${if (type == 0) "QQ邮箱" else "企业微信"}")
                }
                MessageDispatcher.sendMessage("状态查询通知", content)
            }

            notice.contains("截屏") -> {
                if (ProjectionSession.isStateActive()) {
                    openApplication { emitMonitorEvent(MonitorEvent.AppOpenedForScreenshot) }
                } else {
                    MessageDispatcher.sendMessage("截屏状态通知", "截屏服务已断开，截屏失败")
                }
            }

            else -> {
                // 自定义打卡指令，用户可配置关键词（如 "打卡"），同样需要 DT# 前缀
                val key = SaveKeyValues.loadString(Constant.REMOTE_COMMAND_KEY, "打卡")
                if (notice.contains(key)) {
                    // 遥控"打卡"：一次性，只唤起目标 App 并倒计时，不关联任务调度
                    openApplication {
                        serviceScope.launch {
                            val timeoutSeconds = SaveKeyValues.loadInt(
                                Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
                            )
                            val target = SystemClock.elapsedRealtime() + timeoutSeconds * 1000L
                            while (isActive) {
                                val remaining = target - SystemClock.elapsedRealtime()
                                if (remaining <= 0) break
                                FloatingWindowController.updateTime((remaining / 1000).toInt())
                                delay(minOf(1000L, remaining).coerceAtLeast(1))
                            }
                        }
                    }
                }
            }
        }
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