package com.pengxh.daily.app.utils

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.extensions.getResponseHeader
import com.pengxh.daily.app.retrofit.RetrofitServiceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import kotlin.coroutines.resume

/**
 * 统一消息分发器
 * 封装邮件和企业微信两种渠道的分流逻辑，全局复用同一个协程作用域。
 *
 * 必须在 [com.pengxh.daily.app.DailyTaskApplication.onCreate] 中初始化。
 */
object MessageDispatcher {

    private val kTag = "MessageDispatcher"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val CHANNEL_EMAIL = 0
    private const val CHANNEL_WECHAT = 1
    private const val SEND_TIMEOUT_MILLIS = 60_000L

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

    private fun shouldFallback(channelOverride: Int?, onSuccess: (() -> Unit)?): Boolean {
        return channelOverride == null && onSuccess == null
    }

    /**
     * 发送文本消息，根据用户配置自动选择邮件或企业微信渠道
     *
     * @param channelOverride 渠道覆盖：null=走用户配置，0=强制邮箱，1=强制企微
     */
    fun sendMessage(
        title: String,
        content: String,
        channelOverride: Int? = null,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null,
        webhookKeyOverride: String? = null,
        emailConfigOverride: EmailManager.EmailConfig? = null
    ) {
        val primaryChannel = channelOverride
            ?: AppConfig.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
        val messageTitle = AppConfig.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")
        val finalTitle = title.ifBlank { messageTitle }

        // 统一拼接元数据，所有渠道都用同一个完整内容
        val fullContent = buildString {
            appendLine(finalTitle)
            appendLine(content.ifBlank { "未监听到打卡成功的通知，请手动登录检查" })
            appendMetaInfo()
        }

        scope.launch {
            val failure = deliver(
                primaryChannel,
                shouldFallback(channelOverride, onSuccess)
            ) { channel ->
                sendTextOnce(
                    channel, finalTitle, fullContent, webhookKeyOverride, emailConfigOverride
                )
            }
            withContext(Dispatchers.Main) {
                if (failure == null) onSuccess?.invoke() else onFailure?.invoke(failure)
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
        channelOverride: Int? = null,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null
    ) {
        val primaryChannel = channelOverride
            ?: AppConfig.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
        val messageTitle = AppConfig.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")
        val finalTitle = title.ifBlank { messageTitle }
        val fullContent = buildString {
            appendLine(content)
            appendMetaInfo()
        }

        scope.launch {
            val failure = deliver(
                primaryChannel,
                shouldFallback(channelOverride, onSuccess)
            ) { channel ->
                sendAttachmentOnce(channel, finalTitle, fullContent, filePath)
            }
            withContext(Dispatchers.Main) {
                if (failure == null) onSuccess?.invoke() else onFailure?.invoke(failure)
            }
        }
    }

    /**
     * 先按主渠道发送，失败则按 [allowFallback] 切换备用渠道重试。
     *
     * @return null 表示至少一个渠道送达成功；非 null 为最后的失败原因
     */
    private suspend fun deliver(
        primaryChannel: Int,
        allowFallback: Boolean,
        attempt: suspend (channel: Int) -> String?
    ): String? {
        val channels = resolveChannelOrder(primaryChannel, allowFallback)
        if (channels.isEmpty()) return "消息渠道未配置：$primaryChannel"

        var lastFailure: String? = null
        for (channel in channels) {
            // 任何失败形态（超时 / 抛异常）都必须收敛成"错误字符串"，
            // 否则异常会逃出 scope.launch 被静默吞掉，同样丢失告警。
            val failure = try {
                withTimeout(SEND_TIMEOUT_MILLIS) { attempt(channel) }
            } catch (e: TimeoutCancellationException) {
                "发送超时（${SEND_TIMEOUT_MILLIS / 1000}s）"
            } catch (ce: CancellationException) {
                // 真正的任务取消不能被当成发送失败吞掉
                throw ce
            } catch (e: Exception) {
                e.printStackTrace()
                e.message ?: "${e.javaClass.simpleName}"
            }
            if (failure == null) return null

            lastFailure = failure
            Log.w(kTag, "渠道[$channel] 发送失败：$failure")
        }
        Log.e(kTag, "所有渠道均发送失败：$lastFailure")
        return lastFailure
    }

    private fun resolveChannelOrder(primary: Int, allowFallback: Boolean): List<Int> {
        if (primary != CHANNEL_EMAIL && primary != CHANNEL_WECHAT) return emptyList()
        if (!allowFallback) return listOf(primary)
        val fallback = if (primary == CHANNEL_EMAIL) CHANNEL_WECHAT else CHANNEL_EMAIL
        return listOf(primary, fallback)
    }

    private suspend fun sendTextOnce(
        channel: Int,
        title: String,
        fullContent: String,
        webhookKeyOverride: String?,
        emailConfigOverride: EmailManager.EmailConfig?
    ): String? = when (channel) {
        CHANNEL_EMAIL -> sendEmailSuspend(title, fullContent, emailConfigOverride)
        CHANNEL_WECHAT -> sendWechatTextSuspend(fullContent, webhookKeyOverride)
        else -> "消息渠道未配置：$channel"
    }

    private suspend fun sendAttachmentOnce(
        channel: Int,
        title: String,
        fullContent: String,
        filePath: String
    ): String? = when (channel) {
        CHANNEL_EMAIL -> sendAttachmentEmailSuspend(title, fullContent, filePath)
        CHANNEL_WECHAT -> sendWechatImageSuspend(filePath)
        else -> "消息渠道未配置：$channel"
    }

    private suspend fun sendEmailSuspend(
        title: String,
        content: String,
        configOverride: EmailManager.EmailConfig?
    ): String? = suspendCancellableCoroutine { continuation ->
        EmailManager.sendEmail(
            title,
            content,
            onSuccess = {
                if (continuation.isActive) continuation.resume(null)
            },
            onFailure = { error ->
                if (continuation.isActive) continuation.resume(error)
            },
            configOverride = configOverride
        )
    }

    private suspend fun sendAttachmentEmailSuspend(
        title: String,
        content: String,
        filePath: String
    ): String? = suspendCancellableCoroutine { continuation ->
        EmailManager.sendAttachmentEmail(
            title,
            content,
            filePath,
            onSuccess = {
                if (continuation.isActive) continuation.resume(null)
            },
            onFailure = { error ->
                if (continuation.isActive) continuation.resume(error)
            }
        )
    }

    private suspend fun sendWechatTextSuspend(
        content: String,
        webhookKeyOverride: String?
    ): String? {
        return try {
            parseWechatResponse(RetrofitServiceManager.sendMessage(content, webhookKeyOverride))
        } catch (e: Exception) {
            e.printStackTrace()
            e.message ?: "企业微信发送失败"
        }
    }

    private suspend fun sendWechatImageSuspend(imagePath: String): String? {
        return try {
            parseWechatResponse(RetrofitServiceManager.sendImageMessage(imagePath))
        } catch (e: Exception) {
            e.printStackTrace()
            e.message ?: "企业微信发送失败"
        }
    }

    /** @return null 表示成功；非 null 为企业微信返回的错误说明 */
    private fun parseWechatResponse(response: String): String? {
        val (code, message) = response.getResponseHeader()
        return if (code == 0) null else "企业微信返回错误：$message（errcode=$code）"
    }
}
