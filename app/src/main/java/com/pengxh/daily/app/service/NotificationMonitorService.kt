package com.pengxh.daily.app.service

import android.app.Notification
import android.content.Intent
import android.os.BatteryManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.pengxh.daily.app.extensions.backToMainActivity
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.NotificationBean
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.timestampToCompleteDate
import com.pengxh.kt.lite.utils.SaveKeyValues

/**
 * @description: 状态栏监听服务
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/25 23:17
 */
class NotificationMonitorService : NotificationListenerService() {

    private val kTag = "MonitorService"
    private val emailManager by lazy { EmailManager(this) }
    private val batteryManager by lazy { getSystemService(BatteryManager::class.java) }
    private val auxiliaryApp = arrayOf(
        Constant.WECHAT, Constant.WEWORK, Constant.QQ, Constant.TIM, Constant.ZFB
    )

    /**
     * 有可用的并且和通知管理器连接成功时回调
     */
    override fun onListenerConnected() {
        sendBroadcast(Intent(Constant.BROADCAST_NOTICE_LISTENER_CONNECTED_ACTION))
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
        sendBroadcast(Intent(Constant.BROADCAST_NOTICE_LISTENER_CONNECTED_ACTION))

        // 保存指定包名的通知，其他的一律不保存
        if (pkg == Constant.TARGET_APP || pkg in auxiliaryApp) {
            NotificationBean().apply {
                packageName = pkg
                notificationTitle = title
                notificationMsg = notice
                postTime = System.currentTimeMillis().timestampToCompleteDate()
            }.also {
                DatabaseWrapper.insertNotice(it)
            }
        }

        // 钉钉打卡通知
        if (pkg == Constant.TARGET_APP && notice.contains("成功")) {
            backToMainActivity()
            "即将发送通知邮件，请注意查收".show(this)
            emailManager.sendEmail(null, notice, false)
        }

        // 其他消息指令
        if (pkg in auxiliaryApp) {
            if (notice.contains("电量")) {
                val capacity = batteryManager.getIntProperty(
                    BatteryManager.BATTERY_PROPERTY_CAPACITY
                )
                emailManager.sendEmail("查询手机电量通知", "当前手机剩余电量为：${capacity}%", false)
            } else if (notice.contains("启动")) {
                SaveKeyValues.putValue(Constant.TASK_AUTO_START_KEY, true)
                sendBroadcast(Intent(Constant.BROADCAST_START_DAILY_TASK_ACTION))
            } else if (notice.contains("停止")) {
                SaveKeyValues.putValue(Constant.TASK_AUTO_START_KEY, false)
                sendBroadcast(Intent(Constant.BROADCAST_STOP_DAILY_TASK_ACTION))
            } else if (notice.contains("息屏")) {
                sendBroadcast(Intent(Constant.BROADCAST_SHOW_MASK_VIEW_ACTION))
            } else if (notice.contains("打卡记录")) {
                var record = ""
                DatabaseWrapper.loadCurrentDayNotice().forEachIndexed { index, item ->
                    record += "【第${index + 1}次】打卡，结果：${item.notificationMsg}，时间：${item.postTime}\r\n"
                }
                emailManager.sendEmail("当天打卡记录通知", record, false)
            } else {
                val key = SaveKeyValues.getValue(Constant.TASK_NAME_KEY, "打卡") as String
                if (notice.contains(key)) {
                    openApplication(true)
                }
            }
        }
    }

    /**
     * 当有通知移除时会回调
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    override fun onListenerDisconnected() {
        sendBroadcast(Intent(Constant.BROADCAST_NOTICE_LISTENER_DISCONNECTED_ACTION))
    }
}