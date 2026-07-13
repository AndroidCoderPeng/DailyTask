package com.pengxh.daily.app.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivitySettingsBinding
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.ChinaHolidayManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.ProjectionEvent
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.daily.app.vm.MessageViewModel
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.LoadingDialog
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.greenrobot.eventbus.EventBus

class SettingsActivity : KotlinBaseActivity<ActivitySettingsBinding>() {

    private val kTag = "SettingsActivity"
    private val context = this
    private val apps by lazy {
        listOf(
            "钉钉",
            "企业微信",
            "飞书",
            "移动办公M3"
        )
    }
    private val icons by lazy {
        listOf(
            R.drawable.ic_ding_ding,
            R.drawable.ic_wei_xin,
            R.drawable.ic_fei_shu,
            R.mipmap.ic_mobile_m3
        )
    }
    private val channels = arrayListOf("QQ邮箱", "企业微信")
    private val permissionContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val notificationContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val projectionContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val mpr by lazy { getSystemService(MediaProjectionManager::class.java) }
    private val messageViewModel by lazy { ViewModelProvider(this)[MessageViewModel::class.java] }
    private val emailManager by lazy { EmailManager(this) }
    private var syncingSwitchState = false

    override fun initViewBinding(): ActivitySettingsBinding {
        return ActivitySettingsBinding.inflate(layoutInflater)
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
        val index = (SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0)).coerceIn(0, icons.lastIndex)
        binding.iconView.setBackgroundResource(icons[index])

        binding.appVersion.text = BuildConfig.VERSION_NAME
        if (notificationEnable()) {
            turnOnNotificationMonitorService()
        }

        val watermark = DailyTask.getWatermarkText()
        binding.contentView.background = WatermarkDrawable(this, watermark)

        lifecycleScope.launch {
            ChinaHolidayManager.syncResult.collect { result ->
                when (result) {
                    is ChinaHolidayManager.SyncResult.Success -> {
                        LoadingDialog.dismiss()
                        result.content.show(context)
                    }

                    is ChinaHolidayManager.SyncResult.Error -> {
                        LoadingDialog.dismiss()
                        result.message.show(context)
                    }
                }
            }
        }

        // 监听通知服务状态
        lifecycleScope.launch {
            NotificationMonitorService.listenerState.collect { connected ->
                if (connected) {
                    binding.noticeSwitch.isChecked = true
                    binding.noticeTipsView.visibility = View.GONE
                    val sourceType =
                        SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)
                    val targetApp =
                        SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, Constant.DEFAULT_INDEX)
                    if (sourceType == 0 && targetApp == 0) {
                        binding.noticeRadioButton.isChecked = true
                        binding.captureRadioButton.isChecked = false
                    }
                } else {
                    binding.noticeTipsView.text = "服务未开启，无法监听打卡结果和接收远程指令"
                    binding.noticeTipsView.setTextColor(Color.RED)
                    binding.noticeSwitch.isChecked = false
                    binding.noticeRadioButton.isChecked = false
                    binding.noticeTipsView.visibility = View.VISIBLE
                }
            }
        }

        lifecycleScope.launch {
            CaptureImageService.projectionEvents.collect { event ->
                when (event) {
                    ProjectionEvent.Ready -> {
                        binding.captureSwitch.isChecked = true
                        binding.captureTipsView.visibility = View.GONE
                        val sourceType = SaveKeyValues.loadInt(
                            Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX
                        )
                        if (sourceType == 1) {
                            binding.captureRadioButton.isChecked = true
                            binding.noticeRadioButton.isChecked = false
                        }
                    }

                    ProjectionEvent.Failed -> {
                        "截屏服务已断开，已切换到通知模式".show(context)
                        binding.captureSwitch.isChecked = false
                        binding.captureRadioButton.isChecked = false
                        binding.captureTipsView.text = "截屏服务未开启，无法获取打卡结果"
                        binding.captureTipsView.setTextColor(Color.RED)
                        binding.captureTipsView.visibility = View.VISIBLE
                        val targetApp = SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0)
                        if (notificationEnable() && targetApp == 0) {
                            SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                            binding.noticeRadioButton.isChecked = true
                        } else {
                            binding.noticeRadioButton.isChecked = false
                        }
                    }
                }
            }
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
                        val oldPosition = SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0)

                        // 如果 position 没有变化，直接返回
                        if (oldPosition == position) {
                            binding.iconView.setBackgroundResource(icons[position])
                            return
                        }

                        if (position == 1 || position == 2) {
                            // 企业微信或者飞书只能采用截屏获取打卡结果
                            if (binding.captureSwitch.isChecked) {
                                binding.captureRadioButton.isChecked = true
                                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 1)
                                binding.noticeRadioButton.isChecked = false
                            } else {
                                "请先打开截屏服务".show(context)
                                binding.captureRadioButton.isChecked = false
                                return
                            }
                        }

                        // 更新配置
                        binding.iconView.setBackgroundResource(icons[position])
                        SaveKeyValues.saveInt(Constant.TARGET_APP_KEY, position)
                    }
                }).build().show()
        }

        binding.msgChannelLayout.setOnClickListener {
            navigatePageTo<MessageChannelActivity>()
        }

        binding.noticeRadioButton.setOnClickListener {
            val index = SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0)
            if (index != 0) {
                "通知监听仅支持钉钉打卡".show(this)
                binding.noticeRadioButton.isChecked = false
                return@setOnClickListener
            }

            if (binding.noticeSwitch.isChecked) {
                binding.noticeRadioButton.isChecked = true
                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                binding.captureRadioButton.isChecked = false
            } else {
                "请先打开通知监听".show(this)
                binding.noticeRadioButton.isChecked = false
            }
        }

        binding.captureRadioButton.setOnClickListener {
            if (binding.captureSwitch.isChecked) {
                binding.captureRadioButton.isChecked = true
                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 1)
                binding.noticeRadioButton.isChecked = false
            } else {
                "请先打开截屏服务".show(this)
                binding.captureRadioButton.isChecked = false
            }
        }

        binding.taskConfigLayout.setOnClickListener {
            navigatePageTo<TaskConfigActivity>()
        }

        binding.updateHolidayLayout.setOnClickListener {
            LoadingDialog.show(this, "更新中，请稍后...")
            ChinaHolidayManager.updateChinaHolidayData()
        }

        binding.floatingSwitch.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                "核心服务，无法关闭".show(this)
                binding.floatingSwitch.isChecked = true
                return@setOnClickListener
            }
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            overlayPermissionLauncher.launch(intent)
        }

        binding.noticeSwitch.setOnClickListener {
            if (notificationEnable()) {
                "核心服务，无法关闭".show(this)
                binding.noticeSwitch.isChecked = true
                return@setOnClickListener
            }
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            notificationSettingLauncher.launch(intent)
        }

        binding.captureSwitch.setOnClickListener {
            if (ProjectionSession.isStateActive()) {
                "核心服务，无法关闭".show(this)
                binding.captureSwitch.isChecked = true
                return@setOnClickListener
            }
            binding.captureSwitch.isChecked = false
            projectionLauncher.launch(mpr.createScreenCaptureIntent())
        }

        binding.openTestLayout.setOnClickListener {
            openApplication()
        }

        binding.captureTestLayout.setOnClickListener {
            if (!binding.captureSwitch.isChecked) {
                "请先打开截屏服务".show(this)
                return@setOnClickListener
            }

            // 再次确认 session 实际状态
            if (!ProjectionSession.isStateActive()) {
                binding.captureSwitch.isChecked = false
                "截屏授权已失效，请重新授权".show(this)
                return@setOnClickListener
            }

            // 触发截屏
            EventBus.getDefault().post(ApplicationEvent.CaptureScreen)

            // 等待截屏结果
            lifecycleScope.launch {
                val imagePath = withTimeoutOrNull(5000L) {
                    CaptureImageService.captureResults.first()
                }
                if (imagePath.isNullOrEmpty()) {
                    "截图失败，无法获取图像".show(context)
                    return@launch
                }

                val index = SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
                when (index) {
                    0 -> {
                        LoadingDialog.show(context, "邮件发送中，请稍后....")
                        emailManager.sendAttachmentEmail(
                            "邮箱测试", "这是一封测试邮件，不必关注", imagePath, true,
                            onSuccess = {
                                LoadingDialog.dismiss()
                                "发送成功，请注意查收".show(context)
                            },
                            onFailure = {
                                LoadingDialog.dismiss()
                                "发送失败：$it".show(context)
                            })
                    }

                    1 -> {
                        messageViewModel.sendImageMessage(
                            imagePath,
                            onLoading = {
                                if (isFinishing || isDestroyed) return@sendImageMessage
                                LoadingDialog.show(context, "消息发送中，请稍后...")
                            },
                            onSuccess = {
                                if (isFinishing || isDestroyed) return@sendImageMessage
                                LoadingDialog.dismiss()
                            },
                            onFailed = {
                                if (isFinishing || isDestroyed) return@sendImageMessage
                                LoadingDialog.dismiss()
                                it.show(context)
                            })
                    }

                    else -> "消息渠道不支持".show(context)
                }
            }
        }

        binding.gestureDetectSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            SaveKeyValues.saveBoolean(Constant.GESTURE_DETECTOR_KEY, isChecked)
        }

        binding.backToHomeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            SaveKeyValues.saveBoolean(Constant.BACK_TO_HOME_KEY, isChecked)
        }

        binding.powerSaveSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            SaveKeyValues.saveBoolean(Constant.POWER_SAVE_MODE_KEY, isChecked)
        }

        binding.introduceLayout.setOnClickListener {
            navigatePageTo<QuestionAndAnswerActivity>()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(permissionContract) {
        if (Settings.canDrawOverlays(this)) {
            Intent(this, FloatingWindowService::class.java).apply {
                startService(this)
            }
        }
    }

    private val notificationSettingLauncher = registerForActivityResult(notificationContract) {
        if (notificationEnable()) {
            turnOnNotificationMonitorService()
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

        if (ProjectionSession.isStateActive()) {
            Log.d(kTag, "MediaProjection already active, skipping creation")
            return@registerForActivityResult
        }

        Intent(this, CaptureImageService::class.java).apply {
            putExtra("resultCode", it.resultCode)
            putExtra("data", data)
            startForegroundService(this)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            binding.floatingSwitch.isChecked = true
            binding.floatingTipsView.visibility = View.GONE
        } else {
            binding.floatingSwitch.isChecked = false
            binding.floatingTipsView.visibility = View.VISIBLE
            binding.floatingTipsView.text = "服务未开启，打完卡无法自动跳回本软件"
        }

        val type = SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
        if (type in 0..channels.lastIndex) {
            binding.channelView.text = channels[type]
            binding.channelView.setTextColor(R.color.theme_color.convertColor(this))
        } else {
            binding.channelView.text = "未配置"
            binding.channelView.setTextColor(R.color.red.convertColor(this))
        }

        // 先同步通知服务的 UI 状态（switch + tipsView）
        if (notificationEnable()) {
            binding.noticeTipsView.text = "服务状态查询中，请稍后..."
            binding.noticeTipsView.setTextColor(R.color.theme_color.convertColor(this))
            lifecycleScope.launch(Dispatchers.Main) {
                delay(500)
                if (notificationEnable()) {
                    binding.noticeSwitch.isChecked = true
                    binding.noticeTipsView.visibility = View.GONE
                }
            }
        } else {
            binding.noticeTipsView.text = "服务未开启，无法监听打卡结果和接收远程指令"
            binding.noticeTipsView.setTextColor(Color.RED)
            binding.noticeSwitch.isChecked = false
            binding.noticeTipsView.visibility = View.VISIBLE
        }

        // 先同步截屏服务的 UI 状态（switch + tipsView）
        if (ProjectionSession.isStateActive()) {
            binding.captureSwitch.isChecked = true
            binding.captureTipsView.visibility = View.GONE
        } else {
            binding.captureTipsView.text = "截屏服务未开启，无法获取打卡结果"
            binding.captureTipsView.setTextColor(Color.RED)
            binding.captureSwitch.isChecked = false
            binding.captureTipsView.visibility = View.VISIBLE
        }

        // 最后再根据 sourceType 设置 radio button（此时 switch 状态已同步）
        val sourceType = SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)
        val targetApp = SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0)
        if (sourceType == 0) {
            if (notificationEnable() && targetApp == 0) {
                binding.noticeRadioButton.isChecked = true
                binding.captureRadioButton.isChecked = false
            } else {
                binding.noticeRadioButton.isChecked = false
                binding.captureRadioButton.isChecked = false
            }
        } else if (sourceType == 1) {
            // 如果是截屏服务，那还要考虑该服务是否正常开启
            if (ProjectionSession.isStateActive()) {
                binding.captureRadioButton.isChecked = true
                binding.noticeRadioButton.isChecked = false
            } else {
                binding.captureRadioButton.isChecked = false
                binding.noticeRadioButton.isChecked = false
            }
        } else {
            binding.captureRadioButton.isChecked = false
            binding.noticeRadioButton.isChecked = false
        }

        syncingSwitchState = true
        try {
            binding.gestureDetectSwitch.isChecked =
                SaveKeyValues.loadBoolean(Constant.GESTURE_DETECTOR_KEY, true)
            binding.backToHomeSwitch.isChecked =
                SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, false)
            binding.powerSaveSwitch.isChecked =
                SaveKeyValues.loadBoolean(Constant.POWER_SAVE_MODE_KEY, false)
        } finally {
            syncingSwitchState = false
        }
    }

    private fun turnOnNotificationMonitorService() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!isActive) return@launch

                val componentName = ComponentName(context, NotificationMonitorService::class.java)
                val currentState = context.packageManager.getComponentEnabledSetting(componentName)

                if (currentState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    context.packageManager.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    delay(500) // 短暂延迟
                    if (!isActive) return@launch
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
}
