package com.pengxh.daily.app.ui

import android.content.ActivityNotFoundException
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
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.R
import com.pengxh.daily.app.base.KotlinBaseActivity
import com.pengxh.daily.app.databinding.ActivitySettingsBinding
import com.pengxh.daily.app.events.ProjectionEvent
import com.pengxh.daily.app.extensions.convertColor
import com.pengxh.daily.app.extensions.isApplicationExist
import com.pengxh.daily.app.extensions.navigatePageTo
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.extensions.show
import com.pengxh.daily.app.extensions.showActionSheet
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.utils.AppConfig
import com.pengxh.daily.app.utils.ChinaHolidayManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.daily.app.widget.LoadingDialog
import com.pengxh.daily.app.widget.SettingItemView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class SettingsActivity : KotlinBaseActivity<ActivitySettingsBinding>() {

    private val kTag = "SettingsActivity"
    private val context = this@SettingsActivity
    private val apps = listOf("钉钉", "企业微信", "飞书", "移动办公M3")
    private val channels = listOf("QQ邮箱", "企业微信")
    private val permissionContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val notificationContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val projectionContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val mpr by lazy { getSystemService(MediaProjectionManager::class.java) }
    private var syncingSwitchState = false

    override fun initViewBinding(): ActivitySettingsBinding {
        return ActivitySettingsBinding.inflate(layoutInflater)
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
        val index = (AppConfig.loadInt(Constant.TARGET_APP_KEY, 0)).coerceIn(0, apps.lastIndex)
        binding.targetAppItem.rightTextView.text = apps[index]

        binding.versionItem.rightTextView.text = BuildConfig.VERSION_NAME
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
                    binding.noticeItem.switchView.isChecked = true
                    binding.noticeItem.descView.visibility = View.GONE
                    val sourceType =
                        AppConfig.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)
                    val targetApp =
                        AppConfig.loadInt(Constant.TARGET_APP_KEY, Constant.DEFAULT_INDEX)
                    if (sourceType == 0 && targetApp == 0) {
                        binding.noticeRadioButton.isChecked = true
                        binding.captureRadioButton.isChecked = false
                    }
                } else {
                    binding.noticeItem.descView.text = "服务未开启，无法监听打卡结果和接收远程指令"
                    binding.noticeItem.descView.setTextColor(Color.RED)
                    binding.noticeItem.switchView.isChecked = false
                    binding.noticeRadioButton.isChecked = false
                    binding.noticeItem.descView.visibility = View.VISIBLE
                }
            }
        }

        lifecycleScope.launch {
            CaptureImageService.projectionEvents.collect { event ->
                when (event) {
                    ProjectionEvent.Ready -> {
                        binding.captureItem.switchView.isChecked = true
                        binding.captureItem.descView.visibility = View.GONE
                        val sourceType = AppConfig.loadInt(
                            Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX
                        )
                        if (sourceType == 1) {
                            binding.captureRadioButton.isChecked = true
                            binding.noticeRadioButton.isChecked = false
                        }
                    }

                    ProjectionEvent.Failed -> {
                        binding.captureItem.switchView.isChecked = false
                        binding.captureRadioButton.isChecked = false
                        binding.captureItem.descView.text = "截屏服务未开启，无法获取打卡结果"
                        binding.captureItem.descView.setTextColor(Color.RED)
                        binding.captureItem.descView.visibility = View.VISIBLE
                        val targetApp = AppConfig.loadInt(Constant.TARGET_APP_KEY, 0)
                        if (notificationEnable() && targetApp == 0) {
                            AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                            binding.noticeRadioButton.isChecked = true
                            "截屏服务已断开，已切换到通知模式".show(context)
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
        binding.targetAppItem.setOnClickListener {
            showActionSheet(R.layout.sheet_select_target_app) { view, dialog ->
                val switches = listOf(
                    view.findViewById<SettingItemView>(R.id.ddItemView),
                    view.findViewById<SettingItemView>(R.id.wxItemView),
                    view.findViewById<SettingItemView>(R.id.fsItemView),
                    view.findViewById<SettingItemView>(R.id.ydItemView)
                ).map { it.switchView }

                // 开关只作展示，点击穿透给整行
                switches.forEach { switch ->
                    switch.isClickable = false
                    switch.isFocusable = false
                }

                val oldPosition = AppConfig.loadInt(Constant.TARGET_APP_KEY, 0)
                switches.forEachIndexed { index, switch ->
                    switch.isChecked = index == oldPosition
                    switches[index].isEnabled = true
                }

                // 点击 item 行切换选中
                listOf(R.id.ddItemView, R.id.wxItemView, R.id.fsItemView, R.id.ydItemView)
                    .map {
                        view.findViewById<SettingItemView>(it)
                    }
                    .forEachIndexed { index, item ->
                        item.setOnClickListener {
                            switches.forEachIndexed { i, s ->
                                s.isChecked = i == index
                            }
                        }
                    }

                view.findViewById<View>(R.id.confirmButton).setOnClickListener {
                    val position = switches.indexOfFirst { it.isChecked }
                    if (position < 0) {
                        "请选择目标应用".show(this)
                        return@setOnClickListener
                    }

                    when (position) {
                        0 -> {
                            // 钉钉：默认通知监听，通知未开则降级截屏，两者都未开则阻断
                            if (binding.noticeItem.switchView.isChecked) {
                                binding.noticeRadioButton.isChecked = true
                                AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                                binding.captureRadioButton.isChecked = false
                            } else if (binding.captureItem.switchView.isChecked) {
                                binding.captureRadioButton.isChecked = true
                                AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, 1)
                                binding.noticeRadioButton.isChecked = false
                            } else {
                                "请先打开通知监听或截屏服务".show(this)
                                return@setOnClickListener
                            }
                        }

                        1, 2, 3 -> {
                            // 企业微信、飞书、移动办公M3：只能截屏
                            if (binding.captureItem.switchView.isChecked) {
                                binding.captureRadioButton.isChecked = true
                                AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, 1)
                                binding.noticeRadioButton.isChecked = false
                            } else {
                                "请先打开截屏服务".show(this)
                                return@setOnClickListener
                            }
                        }
                    }

                    // 只有通过所有校验后才写入配置
                    binding.targetAppItem.rightTextView.text = apps[position]
                    AppConfig.saveInt(Constant.TARGET_APP_KEY, position)
                    dialog.dismiss()
                    "已切换到${apps[position]}".show(this)
                }
            }
        }

        binding.msgChannelItem.setOnClickListener {
            navigatePageTo<MessageChannelActivity>()
        }

        binding.noticeRadioButton.setOnClickListener {
            val index = AppConfig.loadInt(Constant.TARGET_APP_KEY, 0)
            if (index != 0) {
                "通知监听仅支持钉钉打卡".show(this)
                binding.noticeRadioButton.isChecked = false
                return@setOnClickListener
            }

            if (binding.noticeItem.switchView.isChecked) {
                binding.noticeRadioButton.isChecked = true
                AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                binding.captureRadioButton.isChecked = false
            } else {
                "请先打开通知监听".show(this)
                binding.noticeRadioButton.isChecked = false
            }
        }

        binding.captureRadioButton.setOnClickListener {
            if (binding.captureItem.switchView.isChecked) {
                binding.captureRadioButton.isChecked = true
                AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, 1)
                binding.noticeRadioButton.isChecked = false
            } else {
                "请先打开截屏服务".show(this)
                binding.captureRadioButton.isChecked = false
            }
        }

        binding.taskConfigItem.setOnClickListener {
            navigatePageTo<TaskConfigActivity>()
        }

        binding.updateHolidayItem.setOnClickListener {
            LoadingDialog.show(this, "更新中，请稍后...")
            ChinaHolidayManager.updateChinaHolidayData(forceRefresh = true)
        }

        binding.floatingItem.switchView.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                "核心服务，无法关闭".show(this)
                binding.floatingItem.switchView.isChecked = true
                return@setOnClickListener
            }
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            overlayPermissionLauncher.launch(intent)
        }

        binding.noticeItem.switchView.setOnClickListener {
            if (notificationEnable()) {
                "核心服务，无法关闭".show(this)
                binding.noticeItem.switchView.isChecked = true
                return@setOnClickListener
            }
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            notificationSettingLauncher.launch(intent)
        }

        binding.captureItem.switchView.setOnClickListener {
            if (ProjectionSession.isStateActive()) {
                "核心服务，无法关闭".show(this)
                binding.captureItem.switchView.isChecked = true
                return@setOnClickListener
            }
            binding.captureItem.switchView.isChecked = false
            projectionLauncher.launch(mpr.createScreenCaptureIntent())
        }

        binding.commandItem.setOnClickListener {
            navigatePageTo<CommandDisplayActivity>()
        }

        binding.openTestItem.setOnClickListener {
            openApplication()
        }

        binding.captureTestItem.setOnClickListener {
            if (!binding.captureItem.switchView.isChecked) {
                "请先打开截屏服务".show(this)
                return@setOnClickListener
            }

            // 再次确认 session 实际状态
            if (!ProjectionSession.isStateActive()) {
                binding.captureItem.switchView.isChecked = false
                "截屏授权已失效，请重新授权".show(this)
                return@setOnClickListener
            }

            // 触发截屏并等待截屏结果
            lifecycleScope.launch {
                val imagePath = CaptureImageService.requestCaptureScreen().await()
                if (imagePath == null) {
                    "截屏请求超时，请重试".show(context)
                    return@launch
                }
                if (imagePath.isEmpty()) {
                    "截图失败，无法获取图像".show(context)
                    return@launch
                }

                LoadingDialog.show(context, "消息发送中，请稍后...")
                MessageDispatcher.sendAttachmentMessage(
                    "邮箱测试", "这是一封测试邮件，不必关注", imagePath,
                    onSuccess = {
                        LoadingDialog.dismiss()
                        "发送成功，请注意查收".show(context)
                    },
                    onFailure = {
                        LoadingDialog.dismiss()
                        "发送失败：$it".show(context)
                    })
            }
        }

        binding.gestureItem.switchView.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            AppConfig.saveBoolean(Constant.GESTURE_DETECTOR_KEY, isChecked)
        }

        binding.backHomeItem.switchView.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            AppConfig.saveBoolean(Constant.BACK_TO_HOME_KEY, isChecked)
        }

        binding.powerSaveItem.switchView.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            AppConfig.saveBoolean(Constant.POWER_SAVE_MODE_KEY, isChecked)
            FloatingWindowService.notifyPowerSaveModeChanged()
        }

        binding.remoteClockInCaptureItem.switchView.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            if (isChecked && !ProjectionSession.isStateActive()) {
                syncingSwitchState = true
                binding.remoteClockInCaptureItem.switchView.isChecked = false
                syncingSwitchState = false
                "请先打开截屏服务".show(this)
                return@setOnCheckedChangeListener
            }
            AppConfig.saveBoolean(Constant.REMOTE_CLOCK_IN_CAPTURE_KEY, isChecked)
        }

        binding.sendLogItem.setOnClickListener {
            if (!isApplicationExist(Constant.QQ)) {
                "请先安装QQ".show(context)
                return@setOnClickListener
            }
            LoadingDialog.show(context, "日志打包中，请稍后...")
            lifecycleScope.launch(Dispatchers.IO) {
                val zipFile = LogFileManager.exportLogFiles(context)
                launch(Dispatchers.Main) {
                    LoadingDialog.dismiss()
                    if (zipFile == null) {
                        "暂无可发送的日志".show(context)
                        return@launch
                    }
                    shareLogToQQ(zipFile)
                }
            }
        }

        binding.introduceItem.setOnClickListener {
            navigatePageTo<QuestionAndAnswerActivity>()
        }
    }

    /**
     * 把日志压缩包通过 QQ 发送出去
     * */
    private fun shareLogToQQ(zipFile: File) {
        val uri = FileProvider.getUriForFile(context, "${packageName}.fileprovider", zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            setPackage(Constant.QQ)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            e.printStackTrace()
            "无法唤起QQ".show(context)
        } catch (e: SecurityException) {
            e.printStackTrace()
            "日志分享失败".show(context)
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
            binding.floatingItem.switchView.isChecked = true
            binding.floatingItem.descView.visibility = View.GONE
        } else {
            binding.floatingItem.switchView.isChecked = false
            binding.floatingItem.descView.visibility = View.VISIBLE
            binding.floatingItem.descView.text = "服务未开启，打完卡无法自动跳回本软件"
        }

        val type = AppConfig.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
        if (type in 0..channels.lastIndex) {
            binding.msgChannelItem.rightTextView.text = channels[type]
            binding.msgChannelItem.rightTextView.setTextColor(R.color.theme_color.convertColor(this))
        } else {
            binding.msgChannelItem.rightTextView.text = "未配置"
            binding.msgChannelItem.rightTextView.setTextColor(R.color.red.convertColor(this))
        }

        // 先同步通知服务的 UI 状态（switch + tipsView）
        if (notificationEnable()) {
            binding.noticeItem.descView.text = "服务状态查询中，请稍后..."
            binding.noticeItem.descView.setTextColor(R.color.theme_color.convertColor(this))
            lifecycleScope.launch(Dispatchers.Main) {
                delay(500)
                if (notificationEnable()) {
                    binding.noticeItem.switchView.isChecked = true
                    binding.noticeItem.descView.visibility = View.GONE
                }
            }
        } else {
            binding.noticeItem.descView.text = "服务未开启，无法监听打卡结果和接收远程指令"
            binding.noticeItem.descView.setTextColor(Color.RED)
            binding.noticeItem.switchView.isChecked = false
            binding.noticeItem.descView.visibility = View.VISIBLE
        }

        // 先同步截屏服务的 UI 状态（switch + tipsView）
        if (ProjectionSession.isStateActive()) {
            binding.captureItem.switchView.isChecked = true
            binding.captureItem.descView.visibility = View.GONE
        } else {
            binding.captureItem.descView.text = "截屏服务未开启，无法获取打卡结果"
            binding.captureItem.descView.setTextColor(Color.RED)
            binding.captureItem.switchView.isChecked = false
            binding.captureItem.descView.visibility = View.VISIBLE
        }

        // 最后再根据 sourceType 设置 radio button（此时 switch 状态已同步）
        val sourceType = AppConfig.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)
        val targetApp = AppConfig.loadInt(Constant.TARGET_APP_KEY, 0)
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
            binding.gestureItem.switchView.isChecked =
                AppConfig.loadBoolean(Constant.GESTURE_DETECTOR_KEY, true)
            binding.backHomeItem.switchView.isChecked =
                AppConfig.loadBoolean(Constant.BACK_TO_HOME_KEY, false)
            binding.powerSaveItem.switchView.isChecked =
                AppConfig.loadBoolean(Constant.POWER_SAVE_MODE_KEY, false)
            binding.remoteClockInCaptureItem.switchView.isChecked =
                AppConfig.loadBoolean(Constant.REMOTE_CLOCK_IN_CAPTURE_KEY, false)
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
