package com.pengxh.daily.app.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivitySettingsBinding
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.utils.BroadcastManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.MessageType
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.kt.lite.adapter.NormalRecyclerAdapter
import com.pengxh.kt.lite.adapter.ViewHolder
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.divider.RecyclerViewItemOffsets
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.getStatusBarHeight
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsActivity : KotlinBaseActivity<ActivitySettingsBinding>() {

    private val context = this

    private val marginOffset by lazy { 16.dp2px(this) }
    private val apps by lazy {
        listOf(
            R.drawable.ic_ding_ding,
//            R.drawable.ic_fei_shu,
//            R.drawable.ic_wei_xin
        )
    }
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
                            R.color.theme_color.convertColor(this@SettingsActivity)
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
        BroadcastManager.getDefault().registerReceivers(this, actions, broadcastReceiver)

        val index = SaveKeyValues.getValue(Constant.TARGET_APP_KEY, 0) as Int
        binding.iconView.setBackgroundResource(apps[index])
        val adapter = object : NormalRecyclerAdapter<Int>(
            R.layout.item_app_rv_l, apps.toMutableList()
        ) {
            override fun convertView(viewHolder: ViewHolder, position: Int, item: Int) {
                viewHolder.setImageResource(R.id.imageView, item)
            }
        }
        binding.recyclerView.addItemDecoration(RecyclerViewItemOffsets(0, 0, marginOffset, 0))
        binding.recyclerView.adapter = adapter
        adapter.setOnItemClickedListener(object : NormalRecyclerAdapter.OnItemClickedListener<Int> {
            override fun onItemClicked(position: Int, item: Int) {
                binding.iconView.setBackgroundResource(item)
                SaveKeyValues.putValue(Constant.TARGET_APP_KEY, position)
            }
        })

        binding.appVersion.text = BuildConfig.VERSION_NAME
        if (notificationEnable()) {
            turnOnNotificationMonitorService()
        }

        val watermark = DailyTask.getWatermarkText()
        binding.contentView.background = WatermarkDrawable(this, watermark)
    }

    override fun observeRequestState() {

    }

    override fun initEvent() {
        binding.emailConfigLayout.setOnClickListener {
            navigatePageTo<EmailConfigActivity>()
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

    private val notificationSettingLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (notificationEnable()) {
                turnOnNotificationMonitorService()
            }
        }

    override fun onResume() {
        super.onResume()
        binding.emailSwitch.isChecked = DatabaseWrapper.loadAll().isNotEmpty()
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
        actions.forEach {
            BroadcastManager.getDefault().unregisterReceiver(this, it)
        }
    }
}