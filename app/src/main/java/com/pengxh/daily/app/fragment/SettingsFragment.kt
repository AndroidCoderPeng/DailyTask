package com.pengxh.daily.app.fragment

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.FragmentSettingsBinding
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.ui.EmailConfigActivity
import com.pengxh.daily.app.ui.NoticeRecordActivity
import com.pengxh.daily.app.ui.QuestionAndAnswerActivity
import com.pengxh.daily.app.ui.TaskConfigActivity
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.kt.lite.base.KotlinBaseFragment
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.extensions.setScreenBrightness
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsFragment : KotlinBaseFragment<FragmentSettingsBinding>() {

    private var broadcastReceiver: BroadcastReceiver? = null
    private val emailManager by lazy { EmailManager(requireContext()) }

    override fun setupTopBarLayout() {

    }

    override fun observeRequestState() {

    }

    override fun initViewBinding(
        inflater: LayoutInflater, container: ViewGroup?
    ): FragmentSettingsBinding {
        return FragmentSettingsBinding.inflate(inflater, container, false)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun initOnCreate(savedInstanceState: Bundle?) {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Constant.BROADCAST_NOTICE_LISTENER_CONNECTED_ACTION -> {
                        binding.noticeSwitch.isChecked = true
                        binding.tipsView.visibility = View.GONE
                    }

                    Constant.BROADCAST_NOTICE_LISTENER_DISCONNECTED_ACTION -> {
                        binding.noticeSwitch.isChecked = false
                        binding.tipsView.visibility = View.VISIBLE
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Constant.BROADCAST_NOTICE_LISTENER_CONNECTED_ACTION)
            addAction(Constant.BROADCAST_NOTICE_LISTENER_DISCONNECTED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            requireContext().registerReceiver(broadcastReceiver, filter)
        }

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

        binding.turnoffLightSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                //最低亮度
                requireActivity().window.setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF)
            } else {
                //恢复默认亮度
                requireActivity().window.setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            }
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
        binding.emailSwitch.isChecked = emailManager.isEmailConfigured()
        binding.backToHomeSwitch.isChecked = SaveKeyValues.getValue(
            Constant.BACK_TO_HOME_KEY, false
        ) as Boolean

        if (requireContext().notificationEnable()) {
            binding.tipsView.text = "通知监听服务状态查询中，请稍后"
            binding.tipsView.setTextColor(R.color.theme_color.convertColor(requireContext()))
        } else {
            binding.tipsView.text = "通知监听服务未开启，无法监听打卡通知"
            binding.tipsView.setTextColor(Color.RED)
        }
    }

    private fun turnOnNotificationMonitorService() {
        lifecycleScope.launch(Dispatchers.IO) {
            requireContext().packageManager.setComponentEnabledSetting(
                ComponentName(requireContext(), NotificationMonitorService::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )

            delay(1000)

            requireContext().packageManager.setComponentEnabledSetting(
                ComponentName(requireContext(), NotificationMonitorService::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        broadcastReceiver?.let {
            try {
                requireContext().unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
        }
        broadcastReceiver = null
    }
}