package com.pengxh.daily.app.utils

import android.content.Context
import android.os.BatteryManager
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.vm.MessageViewModel
import com.pengxh.kt.lite.extensions.timestampToDate
import com.pengxh.kt.lite.utils.SaveKeyValues

class MessageDispatcher(private val context: Context, private val viewModel: MessageViewModel) {

    private val emailManager by lazy { EmailManager(context) }
    private val batteryManager by lazy { context.getSystemService(BatteryManager::class.java) }

    fun sendMessage(title: String, content: String) {
        val messageTitle =
            SaveKeyValues.getValue(Constant.MESSAGE_TITLE_KEY, "打卡结果通知") as String

        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val date = System.currentTimeMillis().timestampToDate()

        val channelType = SaveKeyValues.getValue(Constant.CHANNEL_TYPE_KEY, -1) as Int
        when (channelType) {
            0 -> {
                // 企业微信
                val content = buildString {
                    appendLine(title.ifBlank { messageTitle })
                    appendLine(content.ifBlank { "未监听到打卡成功的通知，请手动登录检查" })
                    appendLine("当前日期：$date")
                    appendLine("当前电量：${if (battery >= 0) "$battery%" else "未知"}")
                    append("版本号：${BuildConfig.VERSION_NAME}")
                }
                viewModel.sendMessage(content, {}, {}, {})
            }

            1 -> {
                // QQ邮箱
                val content = buildString {
                    appendLine(content.ifBlank { "未监听到打卡成功的通知，请手动登录检查" })
                    appendLine("当前日期：$date")
                    appendLine("当前电量：${if (battery >= 0) "$battery%" else "未知"}")
                    append("版本号：${BuildConfig.VERSION_NAME}")
                }
                emailManager.sendEmail(title.ifBlank { messageTitle }, content, false)
            }
        }
    }

    fun sendAttachmentMessage(title: String, content: String, filePath: String) {
        val messageTitle =
            SaveKeyValues.getValue(Constant.MESSAGE_TITLE_KEY, "打卡结果通知") as String

        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val date = System.currentTimeMillis().timestampToDate()

        val channelType = SaveKeyValues.getValue(Constant.CHANNEL_TYPE_KEY, -1) as Int
        when (channelType) {
            0 -> {
                // 企业微信（图文消息暂不支持）
//                val content = """
//                               标题：${title.ifBlank { messageTitle }}
//                               内容：${content}
//                               日期：$date
//                               版本号：${BuildConfig.VERSION_NAME}
//                               当前手机电量：${if (battery >= 0) "battery%" else "未知"}
//                              """.trimIndent()
                viewModel.sendImageMessage(filePath, {}, {}, {})
            }

            1 -> {
                // QQ邮箱
                val content = buildString {
                    appendLine(content)
                    appendLine("当前日期：$date")
                    appendLine("当前电量：${if (battery >= 0) "$battery%" else "未知"}")
                    append("版本号：${BuildConfig.VERSION_NAME}")
                }
                emailManager.sendAttachmentEmail(
                    title.ifBlank { messageTitle },
                    content,
                    filePath,
                    false
                )
            }
        }
    }
}