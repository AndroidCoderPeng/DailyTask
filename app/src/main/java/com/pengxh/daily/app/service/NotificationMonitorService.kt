package com.pengxh.daily.app.service

import android.app.Notification
import android.os.BatteryManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pengxh.daily.app.event.ApplicationEvent
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.HttpRequestManager
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.timestampToCompleteDate
import com.pengxh.kt.lite.utils.SaveKeyValues
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
        val extras = sbn.notification.extras
        // 获取接收消息APP的包名
        val pkg = sbn.packageName
        // 获取接收消息的标题
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        // 获取接收消息的内容
        val notice = extras.getString(Notification.EXTRA_TEXT)
        if (notice.isNullOrBlank()) {
            return
        }

        val targetApp = Constant.getTargetApp()

        // 保存指定包名的通知，其他的一律不保存
        if (pkg == targetApp || pkg in auxiliaryApp) {
            NotificationBean().apply {
                packageName = pkg
                notificationTitle = title
                notificationMsg = notice
                postTime = System.currentTimeMillis().timestampToCompleteDate()
            }.also {
                DatabaseWrapper.insertNotice(it)
            }
        }

        // 目标应用打卡通知
        if (pkg == targetApp && notice.contains("成功")) {
            EventBus.getDefault().post(ApplicationEvent.GoBackMainActivity)
            "即将发送通知邮件，请注意查收".show(this)
            sendChannelMessage("", notice)
        }

        // 其他消息指令
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
                    var record = ""
                    var index = 1
                    DatabaseWrapper.loadCurrentDayNotice().forEach {
                        if (it.notificationMsg.contains("考勤打卡")) {
                            record += "【第${index}次】${it.notificationMsg}，时间：${it.postTime}\r\n"
                            index++
                        }
                    }
                    sendChannelMessage("当天考勤记录通知", record)
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
}