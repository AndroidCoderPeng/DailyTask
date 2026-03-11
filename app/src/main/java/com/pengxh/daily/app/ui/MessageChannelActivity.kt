package com.pengxh.daily.app.ui

import android.os.Build
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
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
import com.pengxh.kt.lite.widget.dialog.AlertControlDialog

class MessageChannelActivity : KotlinBaseActivity<ActivityMessageChannelBinding>() {

    private val kTag = "MessageChannelActivity"
    private val context = this
    private val messageViewModel by lazy { ViewModelProvider(this)[MessageViewModel::class.java] }
    private val emailManager by lazy { EmailManager(this) }

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
                binding.emailTitleView.setText(title)
            }
        }
    }

    override fun observeRequestState() {

    }

    override fun initEvent() {
        binding.wxRadioButton.setOnCheckedChangeListener { _, isChecked ->
            binding.qqRadioButton.isChecked = !isChecked
            if (isChecked && !binding.wxKeyView.text.toString().isNotBlank()) {
                SaveKeyValues.putValue(Constant.CHANNEL_TYPE_KEY, 0)
            } else {
                "请先配置微信 Webhook key".show(context)
            }
        }

        binding.sendWxButton.setOnClickListener {
            val key = binding.wxKeyView.text.toString()
            if (key.isBlank()) {
                "企业微信消息 Webhook key 为空".show(context)
                return@setOnClickListener
            }

            SaveKeyValues.putValue(Constant.WX_WEB_HOOK_KEY, key)

            AlertControlDialog.Builder()
                .setContext(this)
                .setTitle("温馨提醒")
                .setMessage("企业微信配置完成，是否发送测试消息？")
                .setNegativeButton("取消")
                .setPositiveButton("好的").setOnDialogButtonClickListener(object :
                    AlertControlDialog.OnDialogButtonClickListener {
                    override fun onCancelClick() {

                    }

                    override fun onConfirmClick() {
                        sendTestMessage()
                    }
                }).build().show()
        }

        binding.qqRadioButton.setOnCheckedChangeListener { _, isChecked ->
            binding.wxRadioButton.isChecked = !isChecked
            if (isChecked) {
                SaveKeyValues.putValue(Constant.CHANNEL_TYPE_KEY, 1)
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

            val title = binding.emailTitleView.text.toString()

            DatabaseWrapper.insertConfig(outbox, authCode, inbox, title)

            sendTestEmail()
        }
    }

    private fun sendTestMessage() {
        messageViewModel.sendMessage(
            "你好！这是来自 DailyTask 的测试消息 🎉",
            onLoading = {
                LoadingDialog.show(this, "消息发送中，请稍后...")
            },
            onSuccess = {
                LoadingDialog.dismiss()
            },
            onFailed = {
                LoadingDialog.dismiss()
                it.show(this)
            })
    }

    private fun sendTestEmail() {
        AlertControlDialog.Builder()
            .setContext(this)
            .setTitle("温馨提醒")
            .setMessage("邮箱配置完成，是否发送测试邮件？")
            .setNegativeButton("取消")
            .setPositiveButton("好的").setOnDialogButtonClickListener(object :
                AlertControlDialog.OnDialogButtonClickListener {
                override fun onCancelClick() {

                }

                override fun onConfirmClick() {
                    LoadingDialog.show(context, "邮件发送中，请稍后....")
                    emailManager.sendEmail(
                        "邮箱测试", "这是一封测试邮件，不必关注",
                        true,
                        onSuccess = {
                            LoadingDialog.dismiss()
                            "发送成功，请注意查收".show(context)
                        },
                        onFailure = {
                            LoadingDialog.dismiss()
                            "发送失败：${it}".show(context)
                        }
                    )
                }
            }).build().show()
    }
}