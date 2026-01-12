package com.pengxh.daily.app.ui

import android.os.Bundle
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityEmailConfigBinding
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.isEmail
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.LoadingDialog
import com.pengxh.kt.lite.widget.dialog.AlertControlDialog

class EmailConfigActivity : KotlinBaseActivity<ActivityEmailConfigBinding>() {

    private val context = this
    private val emailManager by lazy { EmailManager(this) }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        val config = DatabaseWrapper.loadEmailConfig()
        if (config != null) {
            val outbox = if (config.outbox.contains("@qq.com")) {
                config.outbox.dropLast(7)
            } else {
                config.outbox
            }
            binding.emailSendAddressView.setText(outbox)
            binding.emailSendCodeView.setText(config.authCode)
            binding.emailInboxView.setText(config.inbox)
            binding.emailTitleView.setText(config.title)
        }
    }

    override fun initViewBinding(): ActivityEmailConfigBinding {
        return ActivityEmailConfigBinding.inflate(layoutInflater)
    }

    override fun observeRequestState() {

    }

    override fun setupTopBarLayout() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.menu_right) {
                val address = binding.emailSendAddressView.text.toString()
                val outbox = if (address.contains("@qq.com")) {
                    address
                } else {
                    "${address}@qq.com"
                }
                if (outbox.isBlank()) {
                    "发件箱地址为空".show(context)
                    return@setOnMenuItemClickListener true
                }
                if (!outbox.isEmail()) {
                    "发件箱格式错误，请检查".show(context)
                    return@setOnMenuItemClickListener true
                }

                val authCode = binding.emailSendCodeView.text.toString()
                if (authCode.isBlank()) {
                    "发件箱授权码为空".show(context)
                    return@setOnMenuItemClickListener true
                }

                val inbox = binding.emailInboxView.text.toString()
                if (inbox.isBlank()) {
                    "收件箱地址为空".show(context)
                    return@setOnMenuItemClickListener true
                }
                if (!inbox.isEmail()) {
                    "发件箱格式错误，请检查".show(context)
                    return@setOnMenuItemClickListener true
                }

                val title = binding.emailTitleView.text.toString()

                DatabaseWrapper.insertConfig(outbox, authCode, inbox, title)

                sendTestEmail()
            }
            true
        }
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

    override fun initEvent() {

    }
}