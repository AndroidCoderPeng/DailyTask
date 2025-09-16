package com.pengxh.daily.app.extensions

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailAuthenticator
import com.pengxh.daily.app.utils.EmailConfigKit
import com.pengxh.kt.lite.extensions.getSystemService
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.timestampToDate
import java.util.Date
import java.util.Properties
import javax.mail.Message
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

fun String.sendEmail(context: Context, title: String?, isTest: Boolean) {
    val config = EmailConfigKit.getConfig()
    if (config.inboxEmail.isEmpty()) {
        "邮箱地址为空".show(context)
        return
    }

    /*****************************************************************************************/
    /*********************************发送邮件*************************************************/
    /*****************************************************************************************/
    val authenticator = EmailAuthenticator(config.emailSender, config.authCode)
    val pro = Properties()
    pro.apply {
        put("mail.smtp.host", config.senderServer)
        put("mail.smtp.port", config.emailPort)
        put("mail.smtp.auth", "true")
        put("mail.smtp.ssl.checkserveridentity", "true")
        put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
    }
    if (config.emailSender.endsWith("@qq.com")) {
        pro.apply {
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.socketFactory.port", "465")
        }
    } else if (config.emailSender.endsWith("@163.com")) {
        pro.apply {
            put("mail.smtp.starttls.enable", true)
            put("mail.smtp.starttls.required", true)
            put("mail.smtp.ssl.trust", "smtp.163.com") // 只信任163服务器
        }
    } else if (config.emailSender.endsWith("@126.com")) {
        pro.apply {
            put("mail.smtp.starttls.enable", true)
            put("mail.smtp.starttls.required", true)
            put("mail.smtp.ssl.trust", "smtp.126.com") // 只信任126服务器
        }
    } else if (config.emailSender.endsWith("@yeah.net")) {
        pro.apply {
            put("mail.smtp.starttls.enable", true)
            put("mail.smtp.starttls.required", true)
            put("mail.smtp.ssl.trust", "smtp.yeah.net") // 只信任yeah服务器
        }
    }

    val sendMailSession = Session.getDefaultInstance(pro, authenticator)
    val mime = MimeMessage(sendMailSession)
    mime.setFrom(InternetAddress(config.emailSender))
    mime.setRecipient(Message.RecipientType.TO, InternetAddress(config.inboxEmail))
    if (title == null) {
        mime.subject = config.emailTitle
    } else {
        mime.subject = title
    }
    mime.sentDate = Date()
    val mailContent = if (this == "") {
        "未监听到打卡成功的通知，请手动登录检查" + System.currentTimeMillis().timestampToDate()
    } else {
        "${this}，版本号：${BuildConfig.VERSION_NAME}"
    }
    val capacity = context.getSystemService<BatteryManager>()?.getIntProperty(
        BatteryManager.BATTERY_PROPERTY_CAPACITY
    )
    mime.setText("${mailContent}，当前手机剩余电量为：${capacity}%")
    Thread {
        try {
            Transport.send(mime)
            if (isTest) {
                context.sendBroadcast(Intent(Constant.BROADCAST_SEND_EMAIL_SUCCESS_ACTION))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (isTest) {
                val intent = Intent(Constant.BROADCAST_SEND_EMAIL_FAILED_ACTION).apply {
                    putExtra("message", e.message)
                }
                context.sendBroadcast(intent)
            }
        }
    }.start()
}