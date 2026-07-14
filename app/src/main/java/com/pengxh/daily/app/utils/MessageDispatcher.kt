package com.pengxh.daily.app.utils

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.extensions.getResponseHeader
import com.pengxh.daily.app.retrofit.RetrofitServiceManager
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private lateinit var batteryManager: BatteryManager

    fun initialize(context: Context) {
        batteryManager = context.getSystemService(BatteryManager::class.java)
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
        onFailure: ((String) -> Unit)? = null
    ) {
        val channelType = channelOverride
            ?: SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
        val messageTitle = SaveKeyValues.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")

        // 统一拼接元数据，三个路径都用同一个完整内容
        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val fullContent = buildString {
            appendLine(title.ifBlank { messageTitle })
            appendLine(content.ifBlank { "未监听到打卡成功的通知，请手动登录检查" })
            appendLine("当前日期：${LocalDate.now()}")
            appendLine("当前电量：${if (battery >= 0) "$battery%" else "未知"}")
            append("版本号：${BuildConfig.VERSION_NAME}")
        }

        when (channelType) {
            0 -> {
                EmailManager.sendEmail(
                    title.ifBlank { messageTitle },
                    fullContent,
                    onSuccess,
                    onFailure
                )
            }

            1 -> {
                sendViaWechat(fullContent, onSuccess, onFailure)
            }

            else -> {
                Log.w(kTag, "消息渠道不支持: $channelType")
                onFailure?.invoke("消息渠道未配置")
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
        val channelType = channelOverride
            ?: SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
        val messageTitle = SaveKeyValues.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")

        when (channelType) {
            0 -> {
                val battery =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val fullContent = buildString {
                    appendLine(content)
                    appendLine("当前日期：${LocalDate.now()}")
                    appendLine("当前电量：${if (battery >= 0) "$battery%" else "未知"}")
                    append("版本号：${BuildConfig.VERSION_NAME}")
                }
                EmailManager.sendAttachmentEmail(
                    title.ifBlank { messageTitle },
                    fullContent,
                    filePath,
                    onSuccess,
                    onFailure
                )
            }

            1 -> sendImageViaWechat(filePath, onSuccess, onFailure)

            else -> {
                Log.w(kTag, "消息渠道不支持: $channelType")
                onFailure?.invoke("消息渠道未配置")
            }
        }
    }

    private fun sendViaWechat(
        fullContent: String,
        onSuccess: (() -> Unit)?,
        onFailure: ((String) -> Unit)?
    ) {
        scope.launch {
            try {
                val response = RetrofitServiceManager.sendMessage(fullContent)
                handleWechatResponse(response, onSuccess, onFailure)
            } catch (e: Exception) {
                e.printStackTrace()
                if (onSuccess != null || onFailure != null) {
                    withContext(Dispatchers.Main) {
                        onFailure?.invoke(e.message ?: "未知错误")
                    }
                }
            }
        }
    }

    private fun sendImageViaWechat(
        imagePath: String,
        onSuccess: (() -> Unit)?,
        onFailure: ((String) -> Unit)?
    ) {
        scope.launch {
            try {
                val response = RetrofitServiceManager.sendImageMessage(imagePath)
                handleWechatResponse(response, onSuccess, onFailure)
            } catch (e: Exception) {
                e.printStackTrace()
                if (onSuccess != null || onFailure != null) {
                    withContext(Dispatchers.Main) {
                        onFailure?.invoke(e.message ?: "未知错误")
                    }
                }
            }
        }
    }

    private suspend fun handleWechatResponse(
        response: String,
        onSuccess: (() -> Unit)?,
        onFailure: ((String) -> Unit)?
    ) {
        if (onSuccess == null && onFailure == null) return
        val header = response.getResponseHeader()
        withContext(Dispatchers.Main) {
            if (header.first == 0) {
                onSuccess?.invoke()
            } else {
                onFailure?.invoke(header.second)
            }
        }
    }
}
