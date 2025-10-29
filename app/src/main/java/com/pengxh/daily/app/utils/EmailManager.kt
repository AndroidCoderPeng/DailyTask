package com.pengxh.daily.app.utils

import android.content.Context
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
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Properties
import javax.mail.Message
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

class EmailManager(private val context: Context) {
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
        return config.emailSender.isNotEmpty() && config.authCode.isNotEmpty() && config.inboxEmail.isNotEmpty()
    }

    private fun createSmtpProperties(): Properties {
        val props = Properties().apply {
            put("mail.smtp.host", "smtp.qq.com") // 邮箱SMTP服务器地址
            put("mail.smtp.port", "465") // 邮箱SMTP服务器端口
            put("mail.smtp.auth", "true") // 邮箱SMTP服务器是否需要用户验证
            put("mail.smtp.ssl.checkserveridentity", "true")
            put("mail.smtp.ssl.enable", "true") // 启用SSL加密
            put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            put("mail.smtp.socketFactory.port", "465") //Socket工厂端口
        }
        return props
    }

    private fun buildMailContent(content: String): String {
        val baseContent = if (content.isBlank()) {
            "未监听到打卡成功的通知，请手动登录检查 ${System.currentTimeMillis().timestampToDate()}"
        } else {
            "$content，版本号：${BuildConfig.VERSION_NAME}"
        }

        val batteryCapacity = context.getSystemService<BatteryManager>()
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        return "$baseContent，当前手机剩余电量为：${if (batteryCapacity >= 0) "$batteryCapacity%" else "未知"}"
    }

    fun sendEmail(
        title: String?,
        content: String,
        isTest: Boolean,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null
    ) {
        val config = getEmailConfig()

        val authenticator = EmailAuthenticator(config.emailSender, config.authCode)
        val props = createSmtpProperties()

        val session = Session.getDefaultInstance(props, authenticator)
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.emailSender))
            setRecipient(Message.RecipientType.TO, InternetAddress(config.inboxEmail))
            subject = title ?: config.emailTitle
            sentDate = Date()
            setText(buildMailContent(content))
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Transport.send(message)
                if (isTest) {
                    withContext(Dispatchers.Main) {
                        onSuccess?.invoke()
                    }
                }
            } catch (e: Exception) {
                if (isTest) {
                    val errorMessage = when {
                        e.message?.contains("535", ignoreCase = true) == true ->
                            "邮箱认证失败，请检查邮箱账号和授权码是否正确"

                        e.message?.contains("authentication failed", ignoreCase = true) == true ->
                            "邮箱认证失败，请确认使用的是授权码而非登录密码"

                        else -> "邮件发送失败: ${e.javaClass.simpleName} - ${e.message}"
                    }

                    withContext(Dispatchers.Main) {
                        onFailure?.invoke(errorMessage)
                    }
                }
            }
        }
    }
}