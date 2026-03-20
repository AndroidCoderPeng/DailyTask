package com.pengxh.daily.app.ui

import android.os.Build
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pengxh.daily.app.databinding.ActivityMessageChannelBinding
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.vm.MessageViewModel
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.getStatusBarHeight
import com.pengxh.kt.lite.extensions.isEmail
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.LoadingDialog
import com.pengxh.kt.lite.utils.SaveKeyValues

class MessageChannelActivity : KotlinBaseActivity<ActivityMessageChannelBinding>() {

    private val kTag = "MessageChannelActivity"
    private val context = this
    private val messageViewModel by lazy { ViewModelProvider(this)[MessageViewModel::class.java] }
    private val emailManager by lazy { EmailManager() }

    override fun initViewBinding(): ActivityMessageChannelBinding {
        return ActivityMessageChannelBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) { // 16
            binding.toolbar.setPadding(0, getStatusBarHeight(), 0, 0)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        val title = SaveKeyValues.getValue(Constant.MESSAGE_TITLE_KEY, "打卡结果通知") as String
        binding.messageTitleView.setText(title)

        val type = SaveKeyValues.getValue(Constant.CHANNEL_TYPE_KEY, -1) as Int
        if (type == 0) {
            binding.wxRadioButton.isChecked = true
        } else if (type == 1) {
            binding.qqRadioButton.isChecked = true
        }

        val key = SaveKeyValues.getValue(Constant.WX_WEB_HOOK_KEY, "") as String
        if (!key.isBlank()) {
            binding.wxKeyView.setText(key)
        }

        val configs = DatabaseWrapper.loadAll()
        if (configs.isNotEmpty()) {
            configs.last().run {
                val outbox = if (outbox.contains("@qq.com")) {
                    outbox.dropLast(7)
                } else {
                    outbox
                }
                binding.emailSendAddressView.setText(outbox)
                binding.emailSendCodeView.setText(authCode)
                binding.emailInboxView.setText(inbox)
            }
        }
    }

    override fun observeRequestState() {

    }

    override fun initEvent() {
        binding.wxRadioButton.setOnClickListener {
            val key = SaveKeyValues.getValue(Constant.WX_WEB_HOOK_KEY, "") as String
            if (binding.wxRadioButton.isChecked && key.isNotBlank()) {
                SaveKeyValues.putValue(Constant.CHANNEL_TYPE_KEY, 0)
                binding.qqRadioButton.isChecked = false
            } else {
                "请先配置企业微信消息 Webhook key".show(context)
                binding.wxRadioButton.isChecked = false
            }
        }

        binding.sendWxButton.setOnClickListener {
            val key = binding.wxKeyView.text.toString()
            if (key.isBlank()) {
                "企业微信消息 Webhook key 为空".show(context)
                return@setOnClickListener
            }

            SaveKeyValues.putValue(
                Constant.MESSAGE_TITLE_KEY,
                binding.messageTitleView.text.toString().trim()
            )
            SaveKeyValues.putValue(Constant.WX_WEB_HOOK_KEY, key)

            MaterialAlertDialogBuilder(this)
                .setTitle("测试消息")
                .setMessage("企业微信配置完成，可以发送企业微信消息。\n\n是否继续？")
                .setCancelable(false) // 禁止点击外部关闭
                .setPositiveButton("继续") { _, _ ->
                    sendTestMessage()
                }.setNegativeButton("取消", null).show()
        }

        binding.qqRadioButton.setOnClickListener {
            val configs = DatabaseWrapper.loadAll()
            if (binding.qqRadioButton.isChecked && configs.isNotEmpty()) {
                SaveKeyValues.putValue(Constant.CHANNEL_TYPE_KEY, 1)
                binding.wxRadioButton.isChecked = false
            } else {
                "请先配置QQ邮箱".show(context)
                binding.qqRadioButton.isChecked = false
            }
        }

        binding.sendEmailButton.setOnClickListener {
            val address = binding.emailSendAddressView.text.toString()
            val outbox = if (address.contains("@qq.com")) {
                address
            } else {
                "${address}@qq.com"
            }
            if (outbox.isBlank()) {
                "发件箱地址为空".show(context)
                return@setOnClickListener
            }
            if (!outbox.isEmail()) {
                "发件箱格式错误，请检查".show(context)
                return@setOnClickListener
            }

            val authCode = binding.emailSendCodeView.text.toString()
            if (authCode.isBlank()) {
                "发件箱授权码为空".show(context)
                return@setOnClickListener
            }

            val inbox = binding.emailInboxView.text.toString()
            if (inbox.isBlank()) {
                "收件箱地址为空".show(context)
                return@setOnClickListener
            }
            if (!inbox.isEmail()) {
                "发件箱格式错误，请检查".show(context)
                return@setOnClickListener
            }

            SaveKeyValues.putValue(
                Constant.MESSAGE_TITLE_KEY,
                binding.messageTitleView.text.toString().trim()
            )
            DatabaseWrapper.insertConfig(outbox, authCode, inbox)

            sendTestEmail()
        }
    }

    private fun sendTestMessage() {
        val message = """
                        标题：你好！
                        内容：这是来自 DailyTask 的测试消息 🎉
                      """.trimIndent()
        messageViewModel.sendMessage(
            message,
            onLoading = {
                if (isFinishing || isDestroyed) return@sendMessage
                LoadingDialog.show(this, "消息发送中，请稍后...")
            },
            onSuccess = {
                if (isFinishing || isDestroyed) return@sendMessage
                LoadingDialog.dismiss()
            },
            onFailed = {
                if (isFinishing || isDestroyed) return@sendMessage
                LoadingDialog.dismiss()
                it.show(this)
            })
    }

    private fun sendTestEmail() {
        MaterialAlertDialogBuilder(this)
            .setTitle("测试邮件")
            .setMessage("QQ邮箱配置完成，可以发送QQ邮件。\n\n是否继续？")
            .setCancelable(false) // 禁止点击外部关闭
            .setPositiveButton("继续") { _, _ ->
                LoadingDialog.show(context, "邮件发送中，请稍后....")
                emailManager.sendEmail(
                    "邮箱测试", "这是一封测试邮件，不必关注",
                    true,
                    onSuccess = {
                        if (isFinishing || isDestroyed) return@sendEmail
                        LoadingDialog.dismiss()
                        "发送成功，请注意查收".show(context)
                    },
                    onFailure = {
                        if (isFinishing || isDestroyed) return@sendEmail
                        LoadingDialog.dismiss()
                        "发送失败：${it}".show(context)
                    }
                )
            }.setNegativeButton("取消", null).show()
    }
}