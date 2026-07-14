package com.pengxh.daily.app.ui

import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonObject
import com.pengxh.daily.app.databinding.ActivityMessageChannelBinding
import com.pengxh.daily.app.utils.ConfigStore
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.isEmail
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.LoadingDialog
import com.pengxh.kt.lite.utils.SaveKeyValues

class MessageChannelActivity : KotlinBaseActivity<ActivityMessageChannelBinding>() {

    private val context = this

    override fun initViewBinding(): ActivityMessageChannelBinding {
        return ActivityMessageChannelBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        val title = SaveKeyValues.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")
        binding.messageTitleView.setText(title)

        val key = SaveKeyValues.loadString(Constant.WX_WEB_HOOK_KEY, "")
        if (!key.isBlank()) {
            binding.wxKeyView.setText(key)
        }

        val obj = ConfigStore.get().load(Constant.EMAIL_CONFIG_KEY)
        if (!obj.isEmpty) {
            val outbox = obj.get("outbox").asString
            val authCode = obj.get("authCode").asString
            val inbox = obj.get("inbox").asString
            binding.emailSendAddressView.setText(if (outbox.contains("@qq.com")) outbox.dropLast(7) else outbox)
            binding.emailSendCodeView.setText(authCode)
            binding.emailInboxView.setText(inbox)
        }
    }

    override fun observeRequestState() {

    }

    override fun initEvent() {
        binding.sendWxButton.setOnClickListener {
            val key = binding.wxKeyView.text.toString()
            if (key.isBlank()) {
                "企业微信消息 Webhook key 为空".show(this)
                return@setOnClickListener
            }

            SaveKeyValues.saveString(
                Constant.WX_WEB_HOOK_KEY, binding.wxKeyView.text.toString()
            )

            MaterialAlertDialogBuilder(this)
                .setTitle("测试消息")
                .setMessage("企业微信配置完成，可以发送企业微信消息。\n\n是否继续？")
                .setCancelable(false)
                .setPositiveButton("继续") { _, _ ->
                    sendTestMessage()
                }.setNegativeButton("取消", null).show()
        }

        binding.sendEmailButton.setOnClickListener {
            val address = binding.emailSendAddressView.text.toString()
            if (address.isBlank()) {
                binding.emailSendAddressView.shakeIfEmpty()
                "发件箱地址为空".show(context)
                return@setOnClickListener
            }
            val outbox = if (address.contains("@qq.com")) {
                address
            } else {
                "${address}@qq.com"
            }
            if (!outbox.isEmail()) {
                "发件箱格式错误，请检查".show(context)
                return@setOnClickListener
            }

            val authCode = binding.emailSendCodeView.text.toString()
            if (authCode.isBlank()) {
                binding.emailSendCodeView.shakeIfEmpty()
                "发件箱授权码为空".show(context)
                return@setOnClickListener
            }

            val inbox = binding.emailInboxView.text.toString()
            if (inbox.isBlank()) {
                binding.emailInboxView.shakeIfEmpty()
                "收件箱地址为空".show(context)
                return@setOnClickListener
            }
            if (!inbox.isEmail()) {
                "发件箱格式错误，请检查".show(context)
                return@setOnClickListener
            }

            val cacheObj = JsonObject().apply {
                addProperty("outbox", outbox)
                addProperty("authCode", binding.emailSendCodeView.text.toString())
                addProperty("inbox", binding.emailInboxView.text.toString())
            }
            ConfigStore.get().save(Constant.EMAIL_CONFIG_KEY, cacheObj)

            sendTestEmail()
        }
    }

    private fun sendTestMessage() {
        val message = buildString {
            appendLine("你好！")
            append("这是来自 DailyTask 的测试消息 🎉")
        }
        LoadingDialog.show(this, "消息发送中，请稍后...")
        MessageDispatcher.sendMessage(
            "测试消息", message,
            channelOverride = 1,
            onSuccess = {
                if (isFinishing || isDestroyed) return@sendMessage
                LoadingDialog.dismiss()

                SaveKeyValues.saveString(
                    Constant.MESSAGE_TITLE_KEY, binding.messageTitleView.text.toString().trim()
                )

                SaveKeyValues.saveInt(Constant.MSG_CHANNEL_KEY, 1)
            },
            onFailure = {
                if (isFinishing || isDestroyed) return@sendMessage
                LoadingDialog.dismiss()
                it.show(this)
            })
    }

    private fun sendTestEmail() {
        MaterialAlertDialogBuilder(this)
            .setTitle("测试邮件")
            .setMessage("QQ邮箱配置完成，可以发送QQ邮件。\n\n是否继续？")
            .setCancelable(false)
            .setPositiveButton("继续") { _, _ ->
                LoadingDialog.show(context, "邮件发送中，请稍后....")
                MessageDispatcher.sendMessage(
                    "邮箱测试", "这是一封测试邮件，不必关注",
                    channelOverride = 0,
                    onSuccess = {
                        LoadingDialog.dismiss()
                        "发送成功，请注意查收".show(context)

                        SaveKeyValues.saveString(
                            Constant.MESSAGE_TITLE_KEY,
                            binding.messageTitleView.text.toString().trim()
                        )

                        SaveKeyValues.saveInt(Constant.MSG_CHANNEL_KEY, 0)
                    },
                    onFailure = {
                        LoadingDialog.dismiss()
                        "发送失败：${it}".show(context)
                    })
            }.setNegativeButton("取消", null).show()
    }
}
