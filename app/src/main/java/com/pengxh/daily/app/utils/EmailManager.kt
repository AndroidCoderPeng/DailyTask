package com.pengxh.daily.app.utils

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.google.gson.Gson
import com.pengxh.daily.app.BuildConfig
import com.pengxh.kt.lite.extensions.getSystemService
import com.pengxh.kt.lite.extensions.timestampToDate
import com.pengxh.kt.lite.extensions.toJson
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Properties
import javax.mail.Message
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

object EmailManager {
    private val gson by lazy { Gson() }

    fun setEmailConfig(emailConfig: EmailConfig) {
        // 数据持久化
        SaveKeyValues.putValue(Constant.EMAIL_CONFIG_KEY, emailConfig.toJson())
    }

    fun getEmailConfig(): EmailConfig {
        val config = SaveKeyValues.getValue(Constant.EMAIL_CONFIG_KEY, "") as String
        if (config.isNotEmpty()) {
            return gson.fromJson(config, EmailConfig::class.java)
        }
        return EmailConfig()
    }

    fun isEmailConfigured(): Boolean {
        val config = getEmailConfig()
        return config.emailSender.isNotEmpty() &&
                config.authCode.isNotEmpty() &&
                config.senderServer.isNotEmpty() &&
                config.emailPort.isNotEmpty() &&
                config.inboxEmail.isNotEmpty()
    }

    private fun createSmtpProperties(config: EmailConfig): Properties {
        val props = Properties().apply {
            put("mail.smtp.host", config.senderServer)
            put("mail.smtp.port", config.emailPort)
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.checkserveridentity", "true")
            put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
        }

        when {
            config.emailSender.endsWith("@qq.com") -> {
                props["mail.smtp.ssl.enable"] = "true"
                props["mail.smtp.socketFactory.port"] = "465"
            }

            config.emailSender.endsWith("@163.com") -> {
                setupStartTls(props, "smtp.163.com")
            }

            config.emailSender.endsWith("@126.com") -> {
                setupStartTls(props, "smtp.126.com")
            }

            config.emailSender.endsWith("@yeah.net") -> {
                setupStartTls(props, "smtp.yeah.net")
            }
        }
        return props
    }

    private fun setupStartTls(props: Properties, trustHost: String) {
        props.apply {
            put("mail.smtp.starttls.enable", true)
            put("mail.smtp.starttls.required", true)
            put("mail.smtp.ssl.trust", trustHost)
        }
    }

    private fun buildMailContent(content: String, context: Context): String {
        val baseContent = if (content.isBlank()) {
            "未监听到打卡成功的通知，请手动登录检查 ${System.currentTimeMillis().timestampToDate()}"
        } else {
            "$content，版本号：${BuildConfig.VERSION_NAME}"
        }

        val batteryCapacity = context.getSystemService<BatteryManager>()
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?: -1

        return "$baseContent，当前手机剩余电量为：${if (batteryCapacity >= 0) "$batteryCapacity%" else "未知"}"
    }

    private fun handleSendFailure(e: Exception, context: Context, isTest: Boolean) {
        if (!isTest) return

        val errorMessage = when {
            e.message?.contains("535", ignoreCase = true) == true ->
                "邮箱认证失败，请检查邮箱账号和授权码是否正确"

            e.message?.contains("authentication failed", ignoreCase = true) == true ->
                "邮箱认证失败，请确认使用的是授权码而非登录密码"

            else -> "邮件发送失败: ${e.javaClass.simpleName} - ${e.message}"
        }

        val intent = Intent(Constant.BROADCAST_SEND_EMAIL_FAILED_ACTION).apply {
            putExtra("message", errorMessage)
        }
        context.sendBroadcast(intent)
    }

    fun sendEmail(context: Context, title: String?, content: String, isTest: Boolean) {
        val config = getEmailConfig()

        val authenticator = EmailAuthenticator(config.emailSender, config.authCode)
        val props = createSmtpProperties(config)

        val session = Session.getDefaultInstance(props, authenticator)
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.emailSender))
            setRecipient(Message.RecipientType.TO, InternetAddress(config.inboxEmail))
            subject = title ?: config.emailTitle
            sentDate = Date()
            setText(buildMailContent(content, context))
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Transport.send(message)
                if (isTest) {
                    context.sendBroadcast(Intent(Constant.BROADCAST_SEND_EMAIL_SUCCESS_ACTION))
                }
            } catch (e: Exception) {
                handleSendFailure(e, context, isTest)
            }
        }
    }
}