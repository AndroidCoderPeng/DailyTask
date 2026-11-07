package com.pengxh.daily.app.ui

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.pengxh.daily.app.R
import com.pengxh.daily.app.base.KotlinBaseActivity
import com.pengxh.daily.app.databinding.ActivityMessageChannelBinding
import com.pengxh.daily.app.extensions.isEmail
import com.pengxh.daily.app.extensions.show
import com.pengxh.daily.app.extensions.showCustomDialog
import com.pengxh.daily.app.utils.AppConfig
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.widget.LoadingDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageChannelActivity : KotlinBaseActivity<ActivityMessageChannelBinding>() {

    override fun initViewBinding(): ActivityMessageChannelBinding {
        return ActivityMessageChannelBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, systemBars.top, view.paddingRight, systemBars.bottom)
            insets
        }

        WindowInsetsControllerCompat(window, binding.rootView).isAppearanceLightStatusBars = true
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        val title = AppConfig.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")
        binding.messageTitleView.setText(title)

        val key = AppConfig.loadString(Constant.WX_WEB_HOOK_KEY, "")
        if (!key.isBlank()) {
            binding.wxKeyView.setText(key)
        }

        val obj = AppConfig.loadJson(Constant.EMAIL_CONFIG_KEY)
        if (!obj.isEmpty) {
            val outbox = obj.get("outbox").asString
            val authCode = obj.get("authCode").asString
            val inbox = obj.get("inbox").asString
            binding.emailSendAddressView.setText(if (outbox.contains("@qq.com")) outbox.dropLast(7) else outbox)
            binding.emailSendCodeView.setText(authCode)
            binding.emailInboxView.setText(inbox)
        }

        // 恢复上次选择的渠道：0-企业微信，1-邮件
        when (AppConfig.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)) {
            0 -> if (AppConfig.loadString(Constant.WX_WEB_HOOK_KEY, "").isNotBlank()) {
                binding.channelRadioGroup.check(R.id.wxRadioButton)
            }

            1 -> if (!AppConfig.loadJson(Constant.EMAIL_CONFIG_KEY).isEmpty) {
                binding.channelRadioGroup.check(R.id.qqRadioButton)
            }
        }

        refreshChannelState()
    }

    private fun refreshChannelState() {
        // 统一刷新渠道状态，避免重复代码
        fun pillBackgroundColor(configured: Boolean): Int {
            return if (configured) {
                // 成功色 12% 透明度软背景
                ColorUtils.setAlphaComponent(getColor(R.color.success_color), 0x1F)
            } else {
                getColor(R.color.back_ground_color)
            }
        }

        val wxSaved = AppConfig.loadString(Constant.WX_WEB_HOOK_KEY, "").isNotBlank()
        binding.stateTextView.text = if (wxSaved) "已配置" else "未配置"
        binding.stateTextView.setTextColor(getColor(if (wxSaved) R.color.success_color else R.color.text_secondary_color))
        binding.stateCardView.setCardBackgroundColor(pillBackgroundColor(wxSaved))
        binding.updateTimeView.text = if (wxSaved) {
            "配置保存于 ${AppConfig.loadString(Constant.WX_CONFIG_SAVE_TIME_KEY, "")}"
        } else {
            "测试发送成功后才保存"
        }

        val mailSaved = !AppConfig.loadJson(Constant.EMAIL_CONFIG_KEY).isEmpty
        binding.qqStateTextView.text = if (mailSaved) "已配置" else "未配置"
        binding.qqStateTextView.setTextColor(getColor(if (mailSaved) R.color.success_color else R.color.text_secondary_color))
        binding.qqStateCardView.setCardBackgroundColor(pillBackgroundColor(mailSaved))
        binding.qqUpdateTimeView.text = if (mailSaved) {
            "配置保存于 ${AppConfig.loadString(Constant.EMAIL_CONFIG_SAVE_TIME_KEY, "")}"
        } else {
            "测试发送成功后才保存"
        }
    }

    override fun observeRequestState() {

    }

    override fun initEvent() {
        var previousCheckedId = binding.channelRadioGroup.checkedRadioButtonId
        binding.channelRadioGroup.setOnCheckedChangeListener { group, checkedId ->
            val configured = when (checkedId) {
                R.id.wxRadioButton -> {
                    AppConfig.loadString(Constant.WX_WEB_HOOK_KEY, "").isNotBlank()
                }

                R.id.qqRadioButton -> {
                    !AppConfig.loadJson(Constant.EMAIL_CONFIG_KEY).isEmpty
                }

                else -> return@setOnCheckedChangeListener
            }

            if (!configured) {
                group.check(previousCheckedId)
                "请先测试并保存该渠道配置".show(this)
            } else {
                previousCheckedId = checkedId
                AppConfig.saveInt(
                    Constant.MSG_CHANNEL_KEY, if (checkedId == R.id.wxRadioButton) 0 else 1
                )
            }
        }

        binding.sendWxButton.setOnClickListener {
            val key = binding.wxKeyView.text.toString().trim()
            if (key.isBlank()) {
                binding.wxKeyView.shakeIfEmpty()
                "企业微信消息 Webhook key 为空".show(this)
                return@setOnClickListener
            }

            showCustomDialog(R.layout.dialog_send_message, false) { view, dialog ->
                view.findViewById<TextView>(R.id.keyView).text = key

                view.findViewById<MaterialButton>(R.id.confirmButton).setOnClickListener {
                    dialog.dismiss()
                    sendTestMessage(key)
                }

                view.findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener {
                    dialog.dismiss()
                }
            }
        }

        binding.sendEmailButton.setOnClickListener {
            val address = binding.emailSendAddressView.text.toString()
            if (address.isBlank()) {
                binding.emailSendAddressView.shakeIfEmpty()
                "发件箱地址为空".show(this)
                return@setOnClickListener
            }

            val outbox = if (address.contains("@qq.com")) {
                address
            } else {
                "${address}@qq.com"
            }
            if (!outbox.isEmail()) {
                "发件箱格式错误，请检查".show(this)
                return@setOnClickListener
            }

            val authCode = binding.emailSendCodeView.text.toString()
            if (authCode.isBlank()) {
                binding.emailSendCodeView.shakeIfEmpty()
                "发件箱授权码为空".show(this)
                return@setOnClickListener
            }

            val inbox = binding.emailInboxView.text.toString()
            if (inbox.isBlank()) {
                binding.emailInboxView.shakeIfEmpty()
                "收件箱地址为空".show(this)
                return@setOnClickListener
            }

            if (!inbox.isEmail()) {
                "发件箱格式错误，请检查".show(this)
                return@setOnClickListener
            }

            showCustomDialog(R.layout.dialog_send_email, false) { view, dialog ->
                view.findViewById<TextView>(R.id.outboxView).text = outbox
                view.findViewById<TextView>(R.id.inboxView).text = inbox

                view.findViewById<MaterialButton>(R.id.confirmButton).setOnClickListener {
                    dialog.dismiss()
                    sendTestEmail(outbox, authCode, inbox)
                }

                view.findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener {
                    dialog.dismiss()
                }
            }
        }
    }

    private fun sendTestMessage(webhookKey: String) {
        LoadingDialog.show(this, "消息发送中，请稍后...")
        MessageDispatcher.sendTestMessage(
            "消息测试", "你好！这是一条来自 DailyTask 的回推渠道测试消息。",
            webhookKey,
            onSuccess = {
                if (isFinishing || isDestroyed) return@sendTestMessage
                LoadingDialog.dismiss()

                AppConfig.saveString(
                    Constant.MESSAGE_TITLE_KEY,
                    binding.messageTitleView.text.toString().trim()
                )
                AppConfig.saveString(Constant.WX_WEB_HOOK_KEY, webhookKey)
                AppConfig.saveString(
                    Constant.WX_CONFIG_SAVE_TIME_KEY,
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
                )
                refreshChannelState()
                "企业微信配置验证并保存成功".show(this)
            },
            onFailure = {
                if (isFinishing || isDestroyed) return@sendTestMessage
                LoadingDialog.dismiss()
                it.show(this)
            })
    }

    private fun sendTestEmail(outbox: String, authCode: String, inbox: String) {
        LoadingDialog.show(this, "邮件发送中，请稍后....")
        MessageDispatcher.sendTestEmail(
            "邮箱测试", "你好！这是一封来自 DailyTask 的回推渠道测试邮件", outbox, authCode, inbox,
            onSuccess = {
                if (isFinishing || isDestroyed) return@sendTestEmail
                LoadingDialog.dismiss()

                val config = AppConfig.loadJson(Constant.EMAIL_CONFIG_KEY).apply {
                    addProperty("outbox", outbox)
                    addProperty("authCode", authCode)
                    addProperty("inbox", inbox)
                }

                AppConfig.saveString(
                    Constant.MESSAGE_TITLE_KEY,
                    binding.messageTitleView.text.toString().trim()
                )
                AppConfig.saveJson(Constant.EMAIL_CONFIG_KEY, config)
                AppConfig.saveString(
                    Constant.EMAIL_CONFIG_SAVE_TIME_KEY,
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
                )
                refreshChannelState()
                "邮箱配置验证并保存成功".show(this)
            },
            onFailure = {
                if (isFinishing || isDestroyed) return@sendTestEmail
                LoadingDialog.dismiss()
                it.show(this)
            })
    }
}
