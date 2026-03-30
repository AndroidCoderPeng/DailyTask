package com.pengxh.daily.app.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivitySettingsBinding
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.daily.app.vm.MessageViewModel
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.getStatusBarHeight
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.LoadingDialog
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class SettingsActivity : KotlinBaseActivity<ActivitySettingsBinding>() {

    private val kTag = "SettingsActivity"
    private val context = this
    private val apps by lazy {
        listOf(
            "钉钉",
            "企业微信",
            "飞书"
        )
    }
    private val icons by lazy {
        listOf(
            R.drawable.ic_ding_ding,
            R.drawable.ic_wei_xin,
            R.drawable.ic_fei_shu
        )
    }
    private val channels = arrayListOf("企业微信", "QQ邮箱")
    private val projectionContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val mpr by lazy { getSystemService(MediaProjectionManager::class.java) }
    private val messageViewModel by lazy { ViewModelProvider(this)[MessageViewModel::class.java] }
    private val emailManager by lazy { EmailManager() }

    override fun initViewBinding(): ActivitySettingsBinding {
        return ActivitySettingsBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) { // 16
            binding.toolbar.setPadding(0, getStatusBarHeight(), 0, 0)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        EventBus.getDefault().register(this)

        val index = SaveKeyValues.getValue(Constant.TARGET_APP_KEY, 0) as Int
        binding.iconView.setBackgroundResource(icons[index])

        binding.appVersion.text = BuildConfig.VERSION_NAME
        if (notificationEnable()) {
            turnOnNotificationMonitorService()
        }

        val watermark = DailyTask.getWatermarkText()
        binding.contentView.background = WatermarkDrawable(this, watermark)
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun handleApplicationEvent(event: ApplicationEvent) {
        when (event) {
            is ApplicationEvent.ListenerConnected -> {
                binding.noticeTipsView.text = "通知监听服务状态查询中，请稍后"
                binding.noticeTipsView.setTextColor(R.color.theme_color.convertColor(this))
                binding.noticeSwitch.isChecked = true
                binding.noticeTipsView.visibility = View.GONE
            }

            is ApplicationEvent.ListenerDisconnected -> {
                binding.noticeTipsView.text = "通知监听服务未开启，无法监听打卡通知"
                binding.noticeTipsView.setTextColor(Color.RED)
                binding.noticeSwitch.isChecked = false
                binding.noticeTipsView.visibility = View.VISIBLE
            }

            is ApplicationEvent.ProjectionReady -> {
                binding.captureSwitch.isChecked = true
                binding.captureTipsView.visibility = View.GONE
            }

            is ApplicationEvent.ProjectionFailed -> {
                "截屏服务启动失败，请重试".show(this)
                binding.captureSwitch.isChecked = false
                binding.captureTipsView.visibility = View.VISIBLE
            }

            is ApplicationEvent.CaptureCompleted -> {
                val type = SaveKeyValues.getValue(Constant.CHANNEL_TYPE_KEY, -1) as Int
                when (type) {
                    0 -> {
                        // 企业微信
                        messageViewModel.sendImageMessage(
                            event.imagePath, onLoading = {
                                if (isFinishing || isDestroyed) return@sendImageMessage
                                LoadingDialog.show(this, "消息发送中，请稍后...")
                            },
                            onSuccess = {
                                if (isFinishing || isDestroyed) return@sendImageMessage
                                LoadingDialog.dismiss()
                            },
                            onFailed = {
                                if (isFinishing || isDestroyed) return@sendImageMessage
                                LoadingDialog.dismiss()
                                it.show(this)
                            })
                    }

                    1 -> {
                        // QQ邮箱
                        LoadingDialog.show(this, "邮件发送中，请稍后....")
                        emailManager.sendAttachmentEmail(
                            "邮箱测试", "这是一封测试邮件，不必关注", event.imagePath, true,
                            onSuccess = {
                                if (isFinishing || isDestroyed) return@sendAttachmentEmail
                                LoadingDialog.dismiss()
                                "发送成功，请注意查收".show(this)
                            },
                            onFailure = {
                                if (isFinishing || isDestroyed) return@sendAttachmentEmail
                                LoadingDialog.dismiss()
                                "发送失败：${it}".show(this)
                            })
                    }

                    else -> "消息渠道不支持".show(this)
                }
            }

            else -> {}
        }
    }

    override fun observeRequestState() {

    }

    override fun initEvent() {
        binding.targetAppLayout.setOnClickListener {
            BottomActionSheet.Builder()
                .setContext(this)
                .setActionItemTitle(apps)
                .setItemTextColor(R.color.theme_color.convertColor(this))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        val oldPosition = SaveKeyValues.getValue(Constant.TARGET_APP_KEY, 0) as Int

                        // 如果 position 没有变化，直接返回
                        if (oldPosition == position) {
                            binding.iconView.setBackgroundResource(icons[position])
                            return
                        }

                        // 更新配置
                        binding.iconView.setBackgroundResource(icons[position])
                        SaveKeyValues.putValue(Constant.TARGET_APP_KEY, position)
                    }
                }).build().show()
        }

        binding.msgChannelLayout.setOnClickListener {
            navigatePageTo<MessageChannelActivity>()
        }

        binding.noticeRadioButton.setOnClickListener {
            if (binding.noticeSwitch.isChecked) {
                binding.noticeRadioButton.isChecked = true
                SaveKeyValues.putValue(Constant.RESULT_SOURCE_KEY, 0)
                binding.captureRadioButton.isChecked = false
            } else {
                "请先打开通知监听".show(context)
                binding.noticeRadioButton.isChecked = false
            }
        }

        binding.captureRadioButton.setOnClickListener {
            if (binding.captureSwitch.isChecked) {
                binding.captureRadioButton.isChecked = true
                SaveKeyValues.putValue(Constant.RESULT_SOURCE_KEY, 1)
                binding.noticeRadioButton.isChecked = false
            } else {
                "请先打开截屏服务".show(context)
                binding.captureRadioButton.isChecked = false
            }
        }

        binding.taskConfigLayout.setOnClickListener {
            navigatePageTo<TaskConfigActivity>()
        }

        binding.noticeSwitch.setOnClickListener {
            notificationSettingLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.captureSwitch.setOnClickListener {
            projectionLauncher.launch(mpr.createScreenCaptureIntent())
        }

        binding.openTestLayout.setOnClickListener {
            openApplication(false)
        }

        binding.captureTestLayout.setOnClickListener {
            EventBus.getDefault().post(ApplicationEvent.CaptureScreen)
        }

        binding.gestureDetectorSwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.putValue(Constant.GESTURE_DETECTOR_KEY, isChecked)
        }

        binding.backToHomeSwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.putValue(Constant.BACK_TO_HOME_KEY, isChecked)
        }

        binding.notificationLayout.setOnClickListener {
            navigatePageTo<NoticeRecordActivity>()
        }

        binding.introduceLayout.setOnClickListener {
            navigatePageTo<QuestionAndAnswerActivity>()
        }
    }

    private val projectionLauncher = registerForActivityResult(projectionContract) {
        if (it.resultCode != RESULT_OK) {
            "用户拒绝授权".show(this)
            return@registerForActivityResult
        }

        val data = it.data ?: run {
            "授权失败".show(this)
            return@registerForActivityResult
        }

        if (ProjectionSession.state == ProjectionSession.State.ACTIVE) {
            Log.d(kTag, "MediaProjection already active, skipping creation")
            return@registerForActivityResult
        }

        Intent(this, CaptureImageService::class.java).apply {
            putExtra("resultCode", it.resultCode)
            putExtra("data", data)
            startForegroundService(this)
        }
    }

    private val notificationSettingLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (notificationEnable()) {
                turnOnNotificationMonitorService()
            }
        }

    override fun onResume() {
        super.onResume()
        val type = SaveKeyValues.getValue(Constant.CHANNEL_TYPE_KEY, -1) as Int
        when (type) {
            0 -> {
                binding.channelView.text = channels[type]
                binding.channelView.setTextColor(R.color.theme_color.convertColor(this))
            }

            1 -> {
                binding.channelView.text = channels[type]
                binding.channelView.setTextColor(R.color.theme_color.convertColor(this))
            }

            else -> {
                binding.channelView.text = "未配置"
                binding.channelView.setTextColor(R.color.red.convertColor(this))
            }
        }

        val resultSource = SaveKeyValues.getValue(Constant.RESULT_SOURCE_KEY, 0) as Int
        if (resultSource == 0) {
            binding.noticeRadioButton.isChecked = true
        } else {
            binding.captureRadioButton.isChecked = true
        }

        binding.gestureDetectorSwitch.isChecked =
            SaveKeyValues.getValue(Constant.GESTURE_DETECTOR_KEY, false) as Boolean
        binding.backToHomeSwitch.isChecked =
            SaveKeyValues.getValue(Constant.BACK_TO_HOME_KEY, false) as Boolean

        if (notificationEnable()) {
            binding.noticeTipsView.text = "通知监听服务状态查询中，请稍后"
            binding.noticeTipsView.setTextColor(R.color.theme_color.convertColor(this))
            lifecycleScope.launch(Dispatchers.Main) {
                delay(500)
                if (notificationEnable()) {
                    binding.noticeSwitch.isChecked = true
                    binding.noticeTipsView.visibility = View.GONE
                }
            }
        } else {
            binding.noticeTipsView.text = "通知监听服务未开启，无法监听打卡通知"
            binding.noticeTipsView.setTextColor(Color.RED)
            binding.noticeSwitch.isChecked = false
            binding.noticeTipsView.visibility = View.VISIBLE
        }

        if (ProjectionSession.state == ProjectionSession.State.ACTIVE) {
            binding.captureSwitch.isChecked = true
            binding.captureTipsView.visibility = View.GONE
        } else {
            binding.captureTipsView.text = "截屏服务未开启，无法获取打卡结果"
            binding.captureTipsView.setTextColor(Color.RED)
            binding.captureSwitch.isChecked = false
            binding.captureTipsView.visibility = View.VISIBLE
        }
    }

    private fun turnOnNotificationMonitorService() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val componentName = ComponentName(context, NotificationMonitorService::class.java)

                // 检查当前组件状态
                val currentState = context.packageManager.getComponentEnabledSetting(componentName)
                if (currentState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    // 如果已经启用，先禁用
                    context.packageManager.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    delay(500) // 短暂延迟
                }

                // 重新启用
                context.packageManager.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
    }
}