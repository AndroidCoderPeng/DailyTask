package com.pengxh.daily.app.service

import android.app.Notification
import android.os.BatteryManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.HttpRequestManager
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
    private val emailManager by lazy { EmailManager() }
    private val batteryManager by lazy { getSystemService(BatteryManager::class.java) }
    private val auxiliaryApp = arrayOf(Constant.WECHAT, Constant.QQ, Constant.TIM, Constant.ZFB)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 有可用的并且和通知管理器连接成功时回调
     */
    override fun onListenerConnected() {
        EventBus.getDefault().post(ApplicationEvent.ListenerConnected)
    }

    /**
     * 当有新通知到来时会回调
     */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val needMonitor = SaveKeyValues.getValue(Constant.NEED_NOTICE_MONITOR_KEY, true) as Boolean
        if (!needMonitor) {
            Log.d(kTag, "onNotificationPosted: 消息监听已停止")
            return
        }

        val extras = sbn.notification.extras
        val pkg = sbn.packageName
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val notice = extras.getString(Notification.EXTRA_TEXT)
        if (notice.isNullOrBlank()) {
            return
        }

        val targetApp = Constant.getTargetApp()

        // 保存指定包名的通知，其他的一律不保存
        saveTagetNotice(pkg, targetApp, title, notice)

        // 目标应用打卡通知
        if (pkg == targetApp && notice.contains("成功")) {
            EventBus.getDefault().post(ApplicationEvent.GoBackMainActivity)
            "即将发送通知邮件，请注意查收".show(this)
            val messageTitle =
                SaveKeyValues.getValue(Constant.MESSAGE_TITLE_KEY, "打卡结果通知") as String
            sendChannelMessage(title.ifBlank { messageTitle }, notice)
        }

        // 其他消息指令
        handleRemoteCommand(pkg, notice)
    }

    private fun saveTagetNotice(pkg: String, targetApp: String, title: String, notice: String) {
        if (pkg == targetApp || pkg in auxiliaryApp) {
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
        } else {
            Log.d(kTag, "saveTagetNotice: 其他应用通知不处理")
        }
    }

    private fun handleRemoteCommand(pkg: String, notice: String) {
        if (pkg in auxiliaryApp) {
            when {
                notice.contains("电量") -> {
                    val capacity = batteryManager.getIntProperty(
                        BatteryManager.BATTERY_PROPERTY_CAPACITY
                    )
                    sendChannelMessage("查询手机电量通知", "当前手机剩余电量为：${capacity}%")
                }

                notice.contains("启动") -> {
                    EventBus.getDefault().post(ApplicationEvent.StartDailyTask)
                }

                notice.contains("停止") -> {
                    EventBus.getDefault().post(ApplicationEvent.StopDailyTask)
                }

                notice.contains("开始循环") -> {
                    SaveKeyValues.putValue(Constant.TASK_AUTO_START_KEY, true)
                    sendChannelMessage("循环任务状态通知", "循环任务状态已更新为：开启")
                }

                notice.contains("暂停循环") -> {
                    SaveKeyValues.putValue(Constant.TASK_AUTO_START_KEY, false)
                    sendChannelMessage("循环任务状态通知", "循环任务状态已更新为：暂停")
                }

                notice.contains("息屏") -> {
                    EventBus.getDefault().post(ApplicationEvent.ShowMaskView)
                }

                notice.contains("亮屏") -> {
                    EventBus.getDefault().post(ApplicationEvent.HideMaskView)
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

                else -> {
                    val key = SaveKeyValues.getValue(Constant.TASK_COMMAND_KEY, "打卡") as String
                    if (notice.contains(key)) {
                        openApplication(true)
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
        EventBus.getDefault().post(ApplicationEvent.ListenerDisconnected)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}