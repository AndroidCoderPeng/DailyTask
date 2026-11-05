package com.pengxh.daily.app.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonObject
import com.pengxh.daily.app.R
import com.pengxh.daily.app.base.KotlinBaseActivity
import com.pengxh.daily.app.databinding.ActivityMessageChannelBinding
import com.pengxh.daily.app.extensions.isEmail
import com.pengxh.daily.app.extensions.show
import com.pengxh.daily.app.utils.AppConfig
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.widget.LoadingDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageChannelActivity : KotlinBaseActivity<ActivityMessageChannelBinding>() {

    private val context = this

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

        // 恢复上次选择的渠道：0-邮件，1-企业微信，未设置过则默认企业微信
        if (AppConfig.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX) == 0) {
            binding.channelRadioGroup.check(R.id.qqRadioButton)
        }

        refreshChannelState()
    }

    private fun refreshChannelState() {
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

    private fun pillBackgroundColor(configured: Boolean): Int {
        return if (configured) {
            // 成功色 12% 透明度软背景
            ColorUtils.setAlphaComponent(getColor(R.color.success_color), 0x1F)
        } else {
            getColor(R.color.back_ground_color)
        }
    }

    override fun observeRequestState() {

    }

    override fun initEvent() {
        binding.channelRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.wxRadioButton) {
                binding.wxLayout.visibility = View.VISIBLE
                binding.qqLayout.visibility = View.GONE
            } else {
                binding.wxLayout.visibility = View.GONE
                binding.qqLayout.visibility = View.VISIBLE
            }
        }

        binding.sendWxButton.setOnClickListener {
            val key = binding.wxKeyView.text.toString().trim()
            if (key.isBlank()) {
                binding.wxKeyView.shakeIfEmpty()
                "企业微信消息 Webhook key 为空".show(this)
                return@setOnClickListener
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("测试消息")
                .setMessage("先发送测试消息验证 Webhook，验证成功后才会保存配置。\n\n是否继续？")
                .setCancelable(false)
                .setPositiveButton("继续") { _, _ ->
                    sendTestMessage(key)
                }.setNegativeButton("取消", null).show()
//            showCustomDialog(R.layout.dialog_send_message) { view, dialog ->
//
//            }
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

            sendTestEmail(cacheObj)
        }
    }

    private fun sendTestMessage(webhookKey: String) {
        val message = buildString {
            appendLine("你好！")
            append("这是来自 DailyTask 的测试消息 🎉")
        }
        LoadingDialog.show(this, "消息发送中，请稍后...")
        MessageDispatcher.sendMessage(
            "测试消息", message,
            channelOverride = 1,
            webhookKeyOverride = webhookKey,
            onSuccess = {
                if (isFinishing || isDestroyed) return@sendMessage
                LoadingDialog.dismiss()

                AppConfig.saveWebhookAndChannel(webhookKey, 1)

                AppConfig.saveString(
                    Constant.MESSAGE_TITLE_KEY, binding.messageTitleView.text.toString().trim()
                )
                AppConfig.saveString(
                    Constant.WX_CONFIG_SAVE_TIME_KEY,
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
                )
                refreshChannelState()
                "企业微信配置验证并保存成功".show(context)
            },
            onFailure = {
                if (isFinishing || isDestroyed) return@sendMessage
                LoadingDialog.dismiss()
                it.show(this)
            })
    }

    private fun sendTestEmail(config: JsonObject) {
        val emailConfig = EmailManager.EmailConfig(
            config.get("outbox").asString,
            config.get("authCode").asString,
            config.get("inbox").asString
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("测试邮件")
            .setMessage("QQ邮箱配置完成，可以发送QQ邮件。\n\n是否继续？")
            .setCancelable(false)
            .setPositiveButton("继续") { _, _ ->
                LoadingDialog.show(context, "邮件发送中，请稍后....")
                MessageDispatcher.sendMessage(
                    "邮箱测试", "这是一封测试邮件，不必关注",
                    channelOverride = 0,
                    emailConfigOverride = emailConfig,
                    onSuccess = {
                        LoadingDialog.dismiss()
                        "发送成功，请注意查收".show(context)

                        // 仅在测试发送成功后才持久化邮箱配置，与企微侧语义对齐（关联 BUG-019 / DT4-002）
                        AppConfig.saveJson(Constant.EMAIL_CONFIG_KEY, config)

                        AppConfig.saveString(
                            Constant.MESSAGE_TITLE_KEY,
                            binding.messageTitleView.text.toString().trim()
                        )

                        AppConfig.saveInt(Constant.MSG_CHANNEL_KEY, 0)

                        AppConfig.saveString(
                            Constant.EMAIL_CONFIG_SAVE_TIME_KEY,
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
                        )
                        refreshChannelState()
                    },
                    onFailure = {
                        LoadingDialog.dismiss()
                        "发送失败：${it}".show(context)
                    })
            }.setNegativeButton("取消", null).show()
//        showCustomDialog(R.layout.dialog_send_message) { view, dialog ->
//
//        }
    }
}
