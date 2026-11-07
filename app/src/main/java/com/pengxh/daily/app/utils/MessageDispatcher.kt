package com.pengxh.daily.app.utils

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.extensions.getResponseHeader
import com.pengxh.daily.app.retrofit.RetrofitServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * 统一消息分发器
 * 封装邮件和企业微信两种渠道的分流逻辑，全局复用同一个协程作用域。
 *
 * 必须在 [com.pengxh.daily.app.DailyTaskApplication.onCreate] 中初始化。
 */
object MessageDispatcher {

    private val kTag = "MessageDispatcher"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 用可空类型持有，避免未初始化时抛 UninitializedPropertyAccessException，会让"发告警"这个动作本身炸穿主循环。
     */
    private var batteryManager: BatteryManager? = null

    fun initialize(context: Context) {
        batteryManager = try {
            context.getSystemService(BatteryManager::class.java)
        } catch (e: Exception) {
            Log.w(kTag, "BatteryManager 初始化失败", e)
            null
        }
    }

    private fun readBatteryLevel(): Int {
        return try {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (e: Exception) {
            Log.w(kTag, "读取电量失败", e)
            -1
        }
    }

    private fun StringBuilder.appendMetaInfo() {
        val battery = readBatteryLevel()
        appendLine("当前日期：${LocalDate.now()}")
        appendLine("当前电量：${if (battery >= 0) "$battery%" else "未知"}")
        append("版本号：${BuildConfig.VERSION_NAME}")
    }

    fun sendTestMessage(
        title: String,
        content: String,
        webhookKey: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null
    ) {
        // 统一拼接元数据
        val fullContent = buildString {
            appendLine(title)
            appendLine(content)
            appendMetaInfo()
        }

        scope.launch {
            val response = RetrofitServiceManager.sendMessage(fullContent, webhookKey)
            val failure = parseWechatResponse(response)
            withContext(Dispatchers.Main) {
                if (failure == null) onSuccess?.invoke() else onFailure?.invoke(failure)
            }
        }
    }

    private fun parseWechatResponse(response: String): String? {
        val (code, message) = response.getResponseHeader()
        return if (code == 0) null else "企业微信返回错误：$message（errcode=$code）"
    }

    fun sendTestEmail(
        title: String,
        content: String,
        outbox: String,
        authCode: String,
        inbox: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null
    ) {
        // 统一拼接元数据
        val fullContent = buildString {
            appendLine(content)
            appendMetaInfo()
        }

        EmailManager.sendEmail(title, fullContent, outbox, authCode, inbox, onSuccess, onFailure)
    }

    /**
     * 发送文本消息，根据用户配置自动选择邮件或企业微信渠道
     */
    fun sendMessage(
        title: String,
        content: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null
    ) {
        val channel = AppConfig.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
        if (channel == Constant.DEFAULT_INDEX) {
            onFailure?.invoke("消息渠道未配置")
            return
        }

        val messageTitle = AppConfig.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")
        val finalTitle = title.ifBlank { messageTitle }

        // 统一拼接元数据，所有渠道都用同一个完整内容
        val fullContent = buildString {
            if (channel == 0) {
                // 企业微信
                appendLine(finalTitle)
            }
            appendLine(content.ifBlank { "未监听到打卡成功的通知，请手动登录检查" })
            appendMetaInfo()
        }

        scope.launch {
            if (channel == 0) {
                val key = AppConfig.loadString(Constant.WX_WEB_HOOK_KEY, "")
                val response = RetrofitServiceManager.sendMessage(fullContent, key)
                val failure = parseWechatResponse(response)
                withContext(Dispatchers.Main) {
                    if (failure == null) onSuccess?.invoke() else onFailure?.invoke(failure)
                }
            } else {
                val obj = AppConfig.loadJson(Constant.EMAIL_CONFIG_KEY)
                val outbox = obj.get("outbox").asString
                val authCode = obj.get("authCode").asString
                val inbox = obj.get("inbox").asString
                EmailManager.sendEmail(
                    title,
                    fullContent,
                    outbox,
                    authCode,
                    inbox,
                    onSuccess,
                    onFailure
                )
            }
        }
    }

    /**
     * 发送带附件的消息（邮件附件 / 企业微信图片）
     */
    fun sendAttachmentMessage(
        title: String,
        content: String,
        filePath: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null
    ) {
        val channel = AppConfig.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
        if (channel == Constant.DEFAULT_INDEX) {
            onFailure?.invoke("消息渠道未配置")
            return
        }

        val messageTitle = AppConfig.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")
        val finalTitle = title.ifBlank { messageTitle }

        // 统一拼接元数据，所有渠道都用同一个完整内容
        val fullContent = buildString {
            if (channel == 0) {
                // 企业微信
                appendLine(finalTitle)
            }
            appendLine(content)
            appendMetaInfo()
        }

        scope.launch {
            if (channel == 0) {
                val key = AppConfig.loadString(Constant.WX_WEB_HOOK_KEY, "")

                // 先发送文本消息，避免图片消息过大导致发送失败
                RetrofitServiceManager.sendMessage(fullContent, key)

                val file = File(filePath)
                if (!file.exists()) {
                    onFailure?.invoke("截图发送失败，图片文件不存在")
                    sendMessage("截图发送失败", "图片文件不存在")
                    return@launch
                }

                // 微信群机器人图片消息限制 2MB
                val maxBytes = 2 * 1024 * 1024L
                if (file.length() > maxBytes) {
                    onFailure?.invoke("截图发送失败，图片超过2MB限制（当前 ${file.length() / 1024}KB），请截图后手动查看")
                    sendMessage(
                        "截图发送失败",
                        "图片超过2MB限制（当前 ${file.length() / 1024}KB），请截图后手动查看"
                    )
                    return@launch
                }

                val response = RetrofitServiceManager.sendImageMessage(file, key)
                val failure = parseWechatResponse(response)
                withContext(Dispatchers.Main) {
                    if (failure == null) onSuccess?.invoke() else onFailure?.invoke(failure)
                }
            } else {
                val obj = AppConfig.loadJson(Constant.EMAIL_CONFIG_KEY)
                val outbox = obj.get("outbox").asString
                val authCode = obj.get("authCode").asString
                val inbox = obj.get("inbox").asString
                EmailManager.sendAttachmentEmail(
                    finalTitle,
                    fullContent,
                    filePath,
                    outbox,
                    authCode,
                    inbox,
                    onSuccess,
                    onFailure
                )
            }
        }
    }
}
