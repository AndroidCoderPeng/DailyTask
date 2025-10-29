package com.pengxh.daily.app.ui

import android.os.Bundle
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityEmailConfigBinding
import com.pengxh.daily.app.extensions.initImmersionBar
import com.pengxh.daily.app.utils.EmailConfig
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.isEmail
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.LoadingDialog
import com.pengxh.kt.lite.widget.TitleBarView
import com.pengxh.kt.lite.widget.dialog.AlertControlDialog

class EmailConfigActivity : KotlinBaseActivity<ActivityEmailConfigBinding>() {

    private val kTag = "EmailConfigActivity"
    private val context = this
    private val emailManager by lazy { EmailManager(this) }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        val config = emailManager.getEmailConfig()
        val emailSender = if (config.emailSender.contains("@qq.com")) {
            config.emailSender.dropLast(7)
        } else {
            config.emailSender
        }
        binding.emailSendAddressView.setText(emailSender)
        binding.emailSendCodeView.setText(config.authCode)
        binding.emailInboxView.setText(config.inboxEmail)
        binding.emailTitleView.setText(config.emailTitle)
    }

    override fun initViewBinding(): ActivityEmailConfigBinding {
        return ActivityEmailConfigBinding.inflate(layoutInflater)
    }

    override fun observeRequestState() {

    }

    override fun setupTopBarLayout() {
        binding.rootView.initImmersionBar(this, true, R.color.white)
        binding.titleView.setOnClickListener(object : TitleBarView.OnClickListener {
            override fun onLeftClick() {
                finish()
            }

            override fun onRightClick() {
                val address = binding.emailSendAddressView.text.toString()
                val emailSendAddress = if (address.contains("@qq.com")) {
                    address
                } else {
                    "${address}@qq.com"
                }
                if (emailSendAddress.isBlank()) {
                    "发件箱地址为空".show(context)
                    return
                }
                if (!emailSendAddress.isEmail()) {
                    "发件箱格式错误，请检查".show(context)
                    return
                }

                val emailSendCode = binding.emailSendCodeView.text.toString()
                if (emailSendCode.isBlank()) {
                    "发件箱授权码为空".show(context)
                    return
                }

                val emailInboxAddress = binding.emailInboxView.text.toString()
                if (emailInboxAddress.isBlank()) {
                    "收件箱地址为空".show(context)
                    return
                }
                if (!emailInboxAddress.isEmail()) {
                    "发件箱格式错误，请检查".show(context)
                    return
                }

                val emailConfig = EmailConfig(
                    emailSendAddress,
                    emailSendCode,
                    emailInboxAddress,
                    binding.emailTitleView.text.toString()
                )
                emailManager.setEmailConfig(emailConfig)

                AlertControlDialog.Builder()
                    .setContext(context)
                    .setTitle("温馨提醒")
                    .setMessage("邮箱配置完成，是否发送测试邮件？")
                    .setNegativeButton("取消")
                    .setPositiveButton("好的").setOnDialogButtonClickListener(object :
                        AlertControlDialog.OnDialogButtonClickListener {
                        override fun onCancelClick() {

                        }

                        override fun onConfirmClick() {
                            if (emailManager.isEmailConfigured()) {
                                LoadingDialog.show(context, "邮件发送中，请稍后....")
                                emailManager.sendEmail(
                                    "邮箱测试",
                                    "这是一封测试邮件，不必关注",
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
                        }
                    }).build().show()
            }
        })
    }

    override fun initEvent() {

    }
}