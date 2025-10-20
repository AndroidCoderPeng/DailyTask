package com.pengxh.daily.app.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityEmailConfigBinding
import com.pengxh.daily.app.extensions.initImmersionBar
import com.pengxh.daily.app.extensions.sendEmail
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailConfigKit
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.isEmail
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.LoadingDialog
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.TitleBarView
import com.pengxh.kt.lite.widget.dialog.AlertControlDialog

class EmailConfigActivity : KotlinBaseActivity<ActivityEmailConfigBinding>() {

    private val context = this
    private var broadcastReceiver: BroadcastReceiver? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun initOnCreate(savedInstanceState: Bundle?) {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                LoadingDialog.dismiss()
                when (intent?.action) {
                    Constant.BROADCAST_SEND_EMAIL_SUCCESS_ACTION -> {
                        "发送成功，请注意查收".show(this@EmailConfigActivity)
                    }

                    Constant.BROADCAST_SEND_EMAIL_FAILED_ACTION -> {
                        val message = intent.getStringExtra("message")
                        "发送失败：${message}".show(this@EmailConfigActivity)
                    }
                }
            }
        }
        val intentFilter = IntentFilter().apply {
            addAction(Constant.BROADCAST_SEND_EMAIL_SUCCESS_ACTION)
            addAction(Constant.BROADCAST_SEND_EMAIL_FAILED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, intentFilter)
        }

        val config = EmailConfigKit.getConfig()
        binding.emailSendAddressView.setText(config.emailSender)
        binding.emailSendCodeView.setText(config.authCode)
        binding.emailSendServerView.setText(config.senderServer)
        binding.emailSendPortView.setText(config.emailPort)
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
                val emailSendAddress = binding.emailSendAddressView.text.toString()
                if (emailSendAddress.isBlank()) {
                    "发件箱地址为空".show(context)
                    return
                }
                if (!emailSendAddress.isEmail()) {
                    "发件箱格式错误，请检查".show(context)
                    return
                }
                SaveKeyValues.putValue(Constant.EMAIL_SEND_ADDRESS_KEY, emailSendAddress)

                val emailSendCode = binding.emailSendCodeView.text.toString()
                if (emailSendCode.isBlank()) {
                    "发件箱授权码为空".show(context)
                    return
                }
                SaveKeyValues.putValue(Constant.EMAIL_SEND_CODE_KEY, emailSendCode)

                val emailSendServer = binding.emailSendServerView.text.toString()
                if (emailSendServer.isBlank()) {
                    "发件箱服务器为空".show(context)
                    return
                }
                SaveKeyValues.putValue(Constant.EMAIL_SEND_SERVER_KEY, emailSendServer)

                val emailSendPort = binding.emailSendPortView.text.toString()
                if (emailSendPort.isBlank()) {
                    "发件箱服务器端口为空".show(context)
                    return
                }
                SaveKeyValues.putValue(Constant.EMAIL_SEND_PORT_KEY, emailSendPort)

                val emailInboxAddress = binding.emailInboxView.text.toString()
                if (emailInboxAddress.isBlank()) {
                    "收件箱地址为空".show(context)
                    return
                }
                if (!emailInboxAddress.isEmail()) {
                    "发件箱格式错误，请检查".show(context)
                    return
                }
                SaveKeyValues.putValue(Constant.EMAIL_IN_BOX_KEY, emailInboxAddress)

                SaveKeyValues.putValue(
                    Constant.EMAIL_TITLE_KEY, binding.emailTitleView.text.toString()
                )

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
                            if (EmailConfigKit.isEmailConfigured()) {
                                LoadingDialog.show(context, "邮件发送中，请稍后....")
                                "这是一封测试邮件，不必关注".sendEmail(context, "邮箱测试", true)
                            }
                        }
                    }).build().show()
            }
        })
    }

    override fun initEvent() {
        binding.emailSendAddressView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {

            }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {

            }

            @SuppressLint("SetTextI18n")
            override fun afterTextChanged(p0: Editable?) {
                val emailAddress = p0.toString()
                if (emailAddress == "") {
                    binding.emailSendServerView.setText("")
                    binding.emailSendPortView.setText("")
                    return
                }
                if (emailAddress.endsWith("@qq.com")) {
                    binding.emailSendServerView.setText("smtp.qq.com")
                    binding.emailSendPortView.setText("465")
                } else if (emailAddress.endsWith("@163.com")) {
                    binding.emailSendServerView.setText("smtp.163.com")
                    binding.emailSendPortView.setText("587")
                } else if (emailAddress.endsWith("@126.com")) {
                    binding.emailSendServerView.setText("smtp.126.com")
                    binding.emailSendPortView.setText("587")
                } else if (emailAddress.endsWith("@yeah.net")) {
                    binding.emailSendServerView.setText("smtp.yeah.net")
                    binding.emailSendPortView.setText("587")
                } else {
                    binding.emailSendServerView.setText("")
                    binding.emailSendPortView.setText("")
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        broadcastReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
        }
        broadcastReceiver = null
    }
}