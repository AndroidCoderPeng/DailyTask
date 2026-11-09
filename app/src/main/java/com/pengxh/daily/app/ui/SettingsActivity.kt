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
import android.widget.TextView
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
import com.pengxh.daily.app.extensions.showCustomDialog
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : KotlinBaseActivity<ActivitySettingsBinding>() {

    private val kTag = "SettingsActivity"
    private val context = this@SettingsActivity
    private var syncingResultSourceState = false
    private var pendingTargetAppRequest = false
    private val apps = listOf("钉钉", "企业微信", "飞书", "移动办公M3")
    private val channels = listOf("企业微信", "QQ邮箱")
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
        val watermark = DailyTask.getWatermarkText()
        binding.contentView.background = WatermarkDrawable(this, watermark)

        binding.versionItem.rightTextView.text = BuildConfig.VERSION_NAME

        if (notificationEnable()) {
            turnOnNotificationMonitorService()
        }

        binding.commandItem.rightTextView.text = "${CommandDisplayActivity.commands.size} 条"
    }

    override fun observeRequestState() {
        // 监听通知服务状态
        lifecycleScope.launch {
            NotificationMonitorService.listenerState.collect { connected ->
                if (connected) {
                    binding.notificationItem.switchView.isChecked = true
                    val sourceType =
                        AppConfig.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)
                    val targetApp =
                        AppConfig.loadInt(Constant.TARGET_APP_KEY, 0)
                    if (sourceType == 0 && targetApp == 0) {
                        setResultSourceChecked(binding.noticeRadioButton.id)
                        clearResultSourceChecked(binding.captureRadioButton.id)
                    }
                } else {
                    clearResultSourceChecked(binding.noticeRadioButton.id)
                }
            }
        }

        lifecycleScope.launch {
            CaptureImageService.projectionEvents.collect { event ->
                when (event) {
                    ProjectionEvent.Ready -> {
                        val sourceType = AppConfig.loadInt(
                            Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX
                        )
                        if (sourceType == 1) {
                            setResultSourceChecked(binding.captureRadioButton.id)
                            clearResultSourceChecked(binding.noticeRadioButton.id)
                        }
                        // 从目标应用入口授权成功，继续选择流程（截屏模式全部可选）
                        if (pendingTargetAppRequest) {
                            pendingTargetAppRequest = false
                            showTargetAppSheet(restrictToDingDing = false)
                        }
                    }

                    ProjectionEvent.Failed -> {
                        clearResultSourceChecked(binding.captureRadioButton.id)
                        // 授权成功但服务启动失败，解除目标应用挂起流程
                        if (pendingTargetAppRequest) {
                            pendingTargetAppRequest = false
                            "截屏服务启动失败，无法修改目标应用".show(context)
                        }
                        val targetApp = AppConfig.loadInt(Constant.TARGET_APP_KEY, 0)
                        if (notificationEnable() && targetApp == 0) {
                            AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                            setResultSourceChecked(binding.noticeRadioButton.id)
                            clearResultSourceChecked(binding.captureRadioButton.id)
                            "截屏服务已断开，已切换到通知模式".show(context)
                        } else {
                            clearResultSourceChecked(binding.noticeRadioButton.id)
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            ChinaHolidayManager.syncResult.collect { result ->
                when (result) {
                    is ChinaHolidayManager.SyncResult.Success -> {
                        LoadingDialog.dismiss()
                        showCustomDialog(R.layout.dialog_update_result) { view, dialog ->
                            view.findViewById<TextView>(R.id.textView).text = result.content
                            view.findViewById<View>(R.id.confirmButton).setOnClickListener {
                                dialog.dismiss()
                            }
                        }
                    }

                    is ChinaHolidayManager.SyncResult.Error -> {
                        LoadingDialog.dismiss()
                        result.message.show(context)
                    }
                }
            }
        }

        lifecycleScope.launch {
            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

            ChinaHolidayManager.currentYearUpdatedAt.collect { timestamp ->
                binding.updateHolidayItem.rightText = timestamp?.let {
                    formatter.format(Date(it))
                } ?: "暂无时间"
            }
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
            binding.notificationItem.switchView.isChecked = true
            // 目标应用为钉钉时，服务就绪后由 listenerState 流自动勾选通知监听
            if (AppConfig.loadInt(Constant.TARGET_APP_KEY, 0) == 0) {
                AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, 0)
            }
            if (pendingTargetAppRequest) {
                pendingTargetAppRequest = false
                // 授权成功，继续目标应用选择（通知模式仅钉钉）
                showTargetAppSheet(restrictToDingDing = true)
            }
        } else {
            if (pendingTargetAppRequest) {
                pendingTargetAppRequest = false
                "未授予通知监听权限，无法修改目标应用".show(this)
            }
        }
    }

    private val projectionLauncher = registerForActivityResult(projectionContract) {
        if (it.resultCode != RESULT_OK) {
            "用户拒绝授权".show(this)
            if (pendingTargetAppRequest) {
                pendingTargetAppRequest = false
                "未授权截屏服务，无法修改目标应用".show(this)
            }
            return@registerForActivityResult
        }

        val data = it.data ?: run {
            "授权失败".show(this)
            return@registerForActivityResult
        }

        if (ProjectionSession.isStateActive()) {
            Log.d(kTag, "MediaProjection already active, skipping creation")
            if (pendingTargetAppRequest) {
                pendingTargetAppRequest = false
                showTargetAppSheet(restrictToDingDing = false)
            }
            return@registerForActivityResult
        }

        AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, 1)
        Intent(this, CaptureImageService::class.java).apply {
            putExtra("resultCode", it.resultCode)
            putExtra("data", data)
            startForegroundService(this)
        }
    }

    override fun initEvent() {
        // ================== 服务与权限 ==================
        binding.floatingItem.switchView.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                "核心服务，无法关闭".show(this)
                binding.floatingItem.switchView.isChecked = true
                return@setOnClickListener
            }
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            overlayPermissionLauncher.launch(intent)
        }

        binding.notificationItem.switchView.setOnClickListener {
            if (notificationEnable()) {
                binding.notificationItem.switchView.isChecked = true
                "如需关闭，请在系统设置中撤销本应用的通知使用权".show(this)
                return@setOnClickListener
            }
            notificationSettingLauncher.launch(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            )
        }

        binding.radioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (syncingResultSourceState || checkedId == View.NO_ID) {
                return@setOnCheckedChangeListener
            }

            when (checkedId) {
                binding.noticeRadioButton.id -> {
                    if (AppConfig.loadInt(Constant.TARGET_APP_KEY, 0) != 0) {
                        "非钉钉打卡结果由截屏服务判定，通知监听仅用于接收远程指令".show(this)
                        setResultSourceChecked(null)
                    } else if (notificationEnable()) {
                        AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                    } else {
                        "请先开启「通知监听」开关".show(this)
                        setResultSourceChecked(null)
                    }
                }

                binding.captureRadioButton.id -> {
                    if (ProjectionSession.isStateActive()) {
                        AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, 1)
                    } else {
                        "请先授权截屏服务".show(this)
                        setResultSourceChecked(null)
                        projectionLauncher.launch(mpr.createScreenCaptureIntent())
                    }
                }
            }
        }

        // ================== 任务目标 ==================
        binding.targetAppItem.setOnClickListener {
            val sourceType = AppConfig.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)
            when (sourceType) {
                0 -> {
                    // 结果来源 = 通知监听：先校验是否已允许监听通知
                    if (notificationEnable()) {
                        showTargetAppSheet(restrictToDingDing = true)
                    } else {
                        "请先允许监听通知".show(this)
                        pendingTargetAppRequest = true
                        notificationSettingLauncher.launch(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                    }
                }

                1 -> {
                    // 结果来源 = 截屏服务：先校验是否已授权截屏
                    if (ProjectionSession.isStateActive()) {
                        showTargetAppSheet(restrictToDingDing = false)
                    } else {
                        "请先授权截屏服务".show(this)
                        pendingTargetAppRequest = true
                        projectionLauncher.launch(mpr.createScreenCaptureIntent())
                    }
                }

                else -> {
                    // 结果来源尚未选择（通知监听和截屏服务都没勾选），不许修改目标应用
                    "请先选择结果来源（通知监听或截屏服务）".show(this)
                }
            }
        }

        binding.msgChannelItem.setOnClickListener {
            navigatePageTo<MessageChannelActivity>()
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

        // ================== 行为偏好 ==================
        binding.gestureItem.switchView.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            AppConfig.saveBoolean(Constant.GESTURE_DETECTOR_KEY, isChecked)
        }

        binding.powerSaveItem.switchView.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            AppConfig.saveBoolean(Constant.POWER_SAVE_MODE_KEY, isChecked)
            FloatingWindowService.notifyPowerSaveModeChanged()
        }

        binding.backHomeItem.switchView.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitchState) {
                return@setOnCheckedChangeListener
            }
            AppConfig.saveBoolean(Constant.BACK_TO_HOME_KEY, isChecked)
        }

        // ================== 任务 ==================
        binding.taskConfigItem.setOnClickListener {
            navigatePageTo<TaskConfigActivity>()
        }

        binding.updateHolidayItem.setOnClickListener {
            LoadingDialog.show(this, "更新中，请稍后...")
            ChinaHolidayManager.updateChinaHolidayData(forceRefresh = true)
        }

        // ================== 工具 ==================
        binding.commandItem.setOnClickListener {
            navigatePageTo<CommandDisplayActivity>()
        }

        binding.openTestItem.setOnClickListener {
            openApplication()
        }

        binding.captureTestItem.setOnClickListener {
            if (!ProjectionSession.isStateActive()) {
                "请先授权截屏服务".show(this)
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
                    "截屏测试", "这是截屏功能测试消息，不必关注", imagePath,
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
     * 打开目标应用选择面板
     * @param restrictToDingDing true=通知监听模式，仅钉钉可选，其余置灰；false=截屏模式，全部可选
     * */
    private fun showTargetAppSheet(restrictToDingDing: Boolean) {
        showActionSheet(R.layout.sheet_select_target_app) { view, dialog ->
            val items = listOf(
                view.findViewById<SettingItemView>(R.id.ddItemView),
                view.findViewById<SettingItemView>(R.id.wxItemView),
                view.findViewById<SettingItemView>(R.id.fsItemView),
                view.findViewById<SettingItemView>(R.id.ydItemView)
            )
            val switches = items.map { it.switchView }

            // 开关只作展示，点击穿透给整行
            switches.forEach { switch ->
                switch.isClickable = false
                switch.isFocusable = false
            }

            val oldPosition = AppConfig.loadInt(Constant.TARGET_APP_KEY, 0)
            switches.forEachIndexed { index, switch ->
                switch.isChecked = index == oldPosition
                switch.isEnabled = true
            }

            items.forEachIndexed { index, item ->
                if (restrictToDingDing && index != 0) {
                    // 通知监听仅支持钉钉，其余应用置灰不可选
                    item.alpha = 0.4f
                    item.isEnabled = false
                    item.setOnClickListener(null)
                } else {
                    item.alpha = 1f
                    item.isEnabled = true
                    item.setOnClickListener {
                        switches.forEachIndexed { i, s ->
                            s.isChecked = i == index
                        }
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
                        // 钉钉：优先通知监听，未开启则降级截屏，两者都未开则阻断
                        if (notificationEnable()) {
                            setResultSourceChecked(binding.noticeRadioButton.id)
                            AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                            clearResultSourceChecked(binding.captureRadioButton.id)
                        } else if (ProjectionSession.isStateActive()) {
                            setResultSourceChecked(binding.captureRadioButton.id)
                            AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, 1)
                            clearResultSourceChecked(binding.noticeRadioButton.id)
                        } else {
                            "请先打开通知监听或截屏服务".show(this)
                            return@setOnClickListener
                        }
                    }

                    else -> {
                        // 企业微信、飞书、移动办公M3：只能截屏
                        if (ProjectionSession.isStateActive()) {
                            setResultSourceChecked(binding.captureRadioButton.id)
                            AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, 1)
                            clearResultSourceChecked(binding.noticeRadioButton.id)
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

    private fun setResultSourceChecked(checkedId: Int?) {
        syncingResultSourceState = true
        try {
            if (checkedId == null) {
                binding.radioGroup.clearCheck()
            } else {
                binding.radioGroup.check(checkedId)
            }
        } finally {
            syncingResultSourceState = false
        }
    }

    private fun clearResultSourceChecked(radioId: Int) {
        if (binding.radioGroup.checkedRadioButtonId == radioId) {
            setResultSourceChecked(null)
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

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            binding.floatingItem.switchView.isChecked = true
            binding.floatingItem.descView.visibility = View.GONE
        } else {
            binding.floatingItem.switchView.isChecked = false
            binding.floatingItem.descView.visibility = View.VISIBLE
            binding.floatingItem.descView.text = "服务未开启，打完卡无法自动回本软件"
        }

        // 根据实际服务状态与 sourceType 设置 radio button
        val sourceType = AppConfig.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)
        val targetApp = AppConfig.loadInt(Constant.TARGET_APP_KEY, 0)
        if (sourceType == 0) {
            if (notificationEnable() && targetApp == 0) {
                setResultSourceChecked(binding.noticeRadioButton.id)
                clearResultSourceChecked(binding.captureRadioButton.id)
            } else {
                clearResultSourceChecked(binding.noticeRadioButton.id)
                clearResultSourceChecked(binding.captureRadioButton.id)
                if (targetApp != 0) {
                    // 历史遗留的非法组合（通知来源 + 非钉钉应用），纠正为当前可用来源
                    if (ProjectionSession.isStateActive()) {
                        AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, 1)
                    } else {
                        AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)
                    }
                }
            }
        } else if (sourceType == 1) {
            // 如果是截屏服务，那还要考虑该服务是否正常开启
            if (ProjectionSession.isStateActive()) {
                setResultSourceChecked(binding.captureRadioButton.id)
                clearResultSourceChecked(binding.noticeRadioButton.id)
            } else {
                clearResultSourceChecked(binding.captureRadioButton.id)
                clearResultSourceChecked(binding.noticeRadioButton.id)
            }
        } else {
            clearResultSourceChecked(binding.captureRadioButton.id)
            clearResultSourceChecked(binding.noticeRadioButton.id)
        }

        // 通知监听开关只反映系统授权状态（授权入口在 initEvent 中）
        binding.notificationItem.switchView.isChecked = notificationEnable()

        // 角色说明随目标应用变化：钉钉兼任结果判定，非钉钉仅作远程指令通道
        if (targetApp == 0) {
            binding.notificationItem.descView.text = "接收小号远程指令，并判定钉钉打卡结果"
        } else {
            binding.notificationItem.descView.text = "接收小号远程指令（打卡结果由截屏判定）"
        }

        val type = AppConfig.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
        if (type in 0..channels.lastIndex) {
            binding.msgChannelItem.rightTextView.text = channels[type]
            binding.msgChannelItem.rightTextView.setTextColor(R.color.theme_color.convertColor(this))
        } else {
            binding.msgChannelItem.rightTextView.text = "未配置"
            binding.msgChannelItem.rightTextView.setTextColor(R.color.red.convertColor(this))
        }

        // 同步目标应用回显
        val targetAppIndex = AppConfig.loadInt(Constant.TARGET_APP_KEY, 0)
        if (targetAppIndex in apps.indices) {
            binding.targetAppItem.rightTextView.text = apps[targetAppIndex]
            binding.targetAppItem.rightTextView.setTextColor(R.color.theme_color.convertColor(this))
        } else {
            binding.targetAppItem.rightTextView.text = "未设置"
            binding.targetAppItem.rightTextView.setTextColor(R.color.theme_color.convertColor(this))
        }

        syncingSwitchState = true
        try {
            binding.gestureItem.switchView.isChecked =
                AppConfig.loadBoolean(Constant.GESTURE_DETECTOR_KEY, true)
            binding.backHomeItem.switchView.isChecked =
                AppConfig.loadBoolean(Constant.BACK_TO_HOME_KEY, true)
            binding.powerSaveItem.switchView.isChecked =
                AppConfig.loadBoolean(Constant.POWER_SAVE_MODE_KEY, true)
            binding.remoteClockInCaptureItem.switchView.isChecked =
                AppConfig.loadBoolean(Constant.REMOTE_CLOCK_IN_CAPTURE_KEY, true)
        } finally {
            syncingSwitchState = false
        }
    }
}
