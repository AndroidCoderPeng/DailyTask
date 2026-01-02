package com.pengxh.daily.app.fragment

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.FragmentSettingsBinding
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.ui.EmailConfigActivity
import com.pengxh.daily.app.ui.NoticeRecordActivity
import com.pengxh.daily.app.ui.QuestionAndAnswerActivity
import com.pengxh.daily.app.ui.TaskConfigActivity
import com.pengxh.daily.app.utils.BroadcastManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.MessageType
import com.pengxh.kt.lite.base.KotlinBaseFragment
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsFragment : KotlinBaseFragment<FragmentSettingsBinding>() {

    private val kTag = "SettingsFragment"
    private val actions by lazy {
        listOf(
            MessageType.NOTICE_LISTENER_CONNECTED.action,
            MessageType.NOTICE_LISTENER_DISCONNECTED.action
        )
    }
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let {
                when (MessageType.fromAction(it)) {
                    MessageType.NOTICE_LISTENER_CONNECTED -> {
                        binding.tipsView.text = "通知监听服务状态查询中，请稍后"
                        binding.tipsView.setTextColor(
                            R.color.theme_color.convertColor(requireContext())
                        )
                        binding.noticeSwitch.isChecked = true
                        binding.tipsView.visibility = View.GONE
                    }

                    MessageType.NOTICE_LISTENER_DISCONNECTED -> {
                        binding.tipsView.text = "通知监听服务未开启，无法监听打卡通知"
                        binding.tipsView.setTextColor(Color.RED)
                        binding.noticeSwitch.isChecked = false
                        binding.tipsView.visibility = View.VISIBLE
                    }

                    else -> {}
                }
            }
        }
    }

    override fun setupTopBarLayout() {

    }

    override fun observeRequestState() {

    }

    override fun initViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentSettingsBinding {
        return FragmentSettingsBinding.inflate(inflater, container, false)
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        BroadcastManager.getDefault().registerReceivers(
            requireContext(), actions, broadcastReceiver
        )

        binding.appVersion.text = BuildConfig.VERSION_NAME
        if (requireContext().notificationEnable()) {
            turnOnNotificationMonitorService()
        }
    }

    override fun initEvent() {
        binding.emailConfigLayout.setOnClickListener {
            requireContext().navigatePageTo<EmailConfigActivity>()
        }

        binding.taskConfigLayout.setOnClickListener {
            requireContext().navigatePageTo<TaskConfigActivity>()
        }

        binding.noticeSwitch.setOnClickListener {
            notificationSettingLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.openTestLayout.setOnClickListener {
            requireContext().openApplication(false)
        }

        binding.gestureDetectorSwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.putValue(Constant.GESTURE_DETECTOR_KEY, isChecked)
        }

        binding.backToHomeSwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.putValue(Constant.BACK_TO_HOME_KEY, isChecked)
        }

        binding.notificationLayout.setOnClickListener {
            requireContext().navigatePageTo<NoticeRecordActivity>()
        }

        binding.introduceLayout.setOnClickListener {
            requireContext().navigatePageTo<QuestionAndAnswerActivity>()
        }
    }

    private val notificationSettingLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (requireContext().notificationEnable()) {
                turnOnNotificationMonitorService()
            }
        }

    override fun onResume() {
        super.onResume()
        binding.emailSwitch.isChecked = DatabaseWrapper.loadEmailConfig() != null
        binding.gestureDetectorSwitch.isChecked =
            SaveKeyValues.getValue(Constant.GESTURE_DETECTOR_KEY, false) as Boolean
        binding.backToHomeSwitch.isChecked =
            SaveKeyValues.getValue(Constant.BACK_TO_HOME_KEY, false) as Boolean

        if (requireContext().notificationEnable()) {
            binding.tipsView.text = "通知监听服务状态查询中，请稍后"
            binding.tipsView.setTextColor(R.color.theme_color.convertColor(requireContext()))
            lifecycleScope.launch(Dispatchers.Main) {
                delay(500)
                if (requireContext().notificationEnable()) {
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
                val context = requireContext()
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
        actions.forEach {
            BroadcastManager.getDefault().unregisterReceiver(requireContext(), it)
        }
    }
}