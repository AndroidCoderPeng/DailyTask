package com.pengxh.daily.app.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivitySettingsBinding
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.getStatusBarHeight
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import kotlin.system.exitProcess

class SettingsActivity : KotlinBaseActivity<ActivitySettingsBinding>() {

    private val context = this
    private val apps by lazy {
        listOf(
            "钉钉",
//            "企业微信",
//            "飞书",
//            "移动办公M3"
        )
    }
    private val icons by lazy {
        listOf(
            R.drawable.ic_ding_ding,
//            R.drawable.ic_wei_xin,
//            R.drawable.ic_fei_shu,
//            R.mipmap.ic_launcher
        )
    }
    private val channels = arrayListOf("企业微信", "QQ邮箱")

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
                binding.tipsView.text = "通知监听服务状态查询中，请稍后"
                binding.tipsView.setTextColor(R.color.theme_color.convertColor(this))
                binding.noticeSwitch.isChecked = true
                binding.tipsView.visibility = View.GONE
            }

            is ApplicationEvent.ListenerDisconnected -> {
                binding.tipsView.text = "通知监听服务未开启，无法监听打卡通知"
                binding.tipsView.setTextColor(Color.RED)
                binding.noticeSwitch.isChecked = false
                binding.tipsView.visibility = View.VISIBLE
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

                        // 显示重启确认对话框
                        showRestartDialog()
                    }
                }).build().show()
        }

        binding.msgChannelLayout.setOnClickListener {
            navigatePageTo<MessageChannelActivity>()
        }

        binding.taskConfigLayout.setOnClickListener {
            navigatePageTo<TaskConfigActivity>()
        }

        binding.noticeSwitch.setOnClickListener {
            notificationSettingLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.openTestLayout.setOnClickListener {
            openApplication(false)
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

    private fun showRestartDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("目标应用已切换")
            .setMessage("切换目标应用后需要重启才能生效，否则可能出现服务混用的情况。\n\n是否立即重启？")
            .setCancelable(false) // 禁止点击外部关闭
            .setPositiveButton("立即重启") { _, _ ->
                // 杀死当前进程，确保完全重启
                exitProcess(0)
            }.show()
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

        binding.gestureDetectorSwitch.isChecked =
            SaveKeyValues.getValue(Constant.GESTURE_DETECTOR_KEY, false) as Boolean
        binding.backToHomeSwitch.isChecked =
            SaveKeyValues.getValue(Constant.BACK_TO_HOME_KEY, false) as Boolean

        if (notificationEnable()) {
            binding.tipsView.text = "通知监听服务状态查询中，请稍后"
            binding.tipsView.setTextColor(R.color.theme_color.convertColor(this))
            lifecycleScope.launch(Dispatchers.Main) {
                delay(500)
                if (notificationEnable()) {
                    binding.noticeSwitch.isChecked = true
                    binding.tipsView.visibility = View.GONE
                }
            }
        } else {
            binding.tipsView.text = "通知监听服务未开启，无法监听打卡通知"
            binding.tipsView.setTextColor(Color.RED)
            binding.noticeSwitch.isChecked = false
            binding.tipsView.visibility = View.VISIBLE
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