package com.pengxh.daily.app.utils

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.kt.lite.extensions.getSystemService
import com.pengxh.kt.lite.extensions.timestampToDate
import com.pengxh.kt.lite.extensions.toJson
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
    private val kTag = "EmailManager"

    private fun createSmtpProperties(): Properties {
        val props = Properties().apply {
            put("mail.smtp.host", "smtp.qq.com")
            put("mail.smtp.port", "465")
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.checkserveridentity", "true")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            put("mail.smtp.socketFactory.port", "465")
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
        val configs = DatabaseWrapper.loadAll()
        if (configs.isEmpty()) {
            onFailure?.invoke("邮箱未配置，无法发送邮件")
            return
        }

        val config = configs.last()
        Log.d(kTag, "邮箱配置: ${config.toJson()}")

        val authenticator = EmailAuthenticator(config.outbox, config.authCode)
        val props = createSmtpProperties()

        val session = Session.getInstance(props, authenticator)
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.outbox))
            setRecipient(Message.RecipientType.TO, InternetAddress(config.inbox))
            subject = title ?: config.title
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