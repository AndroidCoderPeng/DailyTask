package com.pengxh.daily.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityTaskConfigBinding
import com.pengxh.daily.app.extensions.isApplicationExist
import com.pengxh.daily.app.model.ExportDataModel
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.sqlite.bean.EmailConfigBean
import com.pengxh.daily.app.utils.AlarmScheduler
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.ChinaHolidayCalendar
import com.pengxh.daily.app.utils.ChinaHolidayRemoteUpdater
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.isNumber
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.toJson
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskConfigActivity : KotlinBaseActivity<ActivityTaskConfigBinding>() {

    private val kTag = "TaskConfigActivity"
    private val context = this
    private val hourArray = arrayListOf("0", "1", "2", "3", "4", "5", "6", "自定义（单位：时）")
    private val timeArray = arrayListOf("15", "30", "45", "自定义（单位：秒）")
    private val optionArray = arrayListOf("QQ", "微信", "TIM", "支付宝", "剪切板")
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val statusTimeFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    }

    override fun initViewBinding(): ActivityTaskConfigBinding {
        return ActivityTaskConfigBinding.inflate(layoutInflater)
    }

    override fun observeRequestState() {

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
        EventBus.getDefault().register(this)
        val hour = SaveKeyValues.getValue(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        ) as Int
        binding.resetTimeView.text = "每天${hour}点"
        val time = SaveKeyValues.getValue(
            Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
        ) as Int
        binding.timeoutTextView.text = "${time}s"
        binding.keyTextView.text =
            SaveKeyValues.getValue(Constant.TASK_COMMAND_KEY, "打卡") as String
        binding.autoTaskSwitch.isChecked = SaveKeyValues.getValue(
            Constant.TASK_AUTO_START_KEY, true
        ) as Boolean
        binding.skipHolidaySwitch.isChecked = SaveKeyValues.getValue(
            Constant.SKIP_CHINA_HOLIDAY_KEY, false
        ) as Boolean
        val needRandom = SaveKeyValues.getValue(Constant.RANDOM_TIME_KEY, true) as Boolean
        binding.randomTimeSwitch.isChecked = needRandom
        if (needRandom) {
            binding.minuteRangeLayout.visibility = View.VISIBLE
            val value = SaveKeyValues.getValue(Constant.RANDOM_MINUTE_RANGE_KEY, 5) as Int
            binding.minuteRangeView.text = "${value}分钟"
        } else {
            binding.minuteRangeLayout.visibility = View.GONE
        }
        updateHolidayDataStatus()
    }

    override fun initEvent() {
        binding.resetTimeLayout.setOnClickListener {
            BottomActionSheet.Builder()
                .setContext(this)
                .setActionItemTitle(hourArray)
                .setItemTextColor(R.color.theme_color.convertColor(this))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        setHourByPosition(position)
                    }
                }).build().show()
        }

        binding.timeoutLayout.setOnClickListener {
            BottomActionSheet.Builder()
                .setContext(this)
                .setActionItemTitle(timeArray)
                .setItemTextColor(R.color.theme_color.convertColor(this))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        setTimeByPosition(position)
                    }
                }).build().show()
        }

        binding.keyLayout.setOnClickListener {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置打卡口令")
                .setHintMessage("请输入打卡口令，如：打卡")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        SaveKeyValues.putValue(Constant.TASK_COMMAND_KEY, value)
                        binding.keyTextView.text = value
                    }

                    override fun onCancelClick() {}
                }).build().show()
        }

        binding.randomTimeSwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.putValue(Constant.RANDOM_TIME_KEY, isChecked)
            if (isChecked) {
                binding.minuteRangeLayout.visibility = View.VISIBLE
                val value = SaveKeyValues.getValue(Constant.RANDOM_MINUTE_RANGE_KEY, 5) as Int
                binding.minuteRangeView.text = "${value}分钟"
            } else {
                binding.minuteRangeLayout.visibility = View.GONE
            }
        }

        binding.skipHolidaySwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.putValue(Constant.SKIP_CHINA_HOLIDAY_KEY, isChecked)
            updateHolidayDataStatus()
            if (isChecked) {
                ChinaHolidayRemoteUpdater.refreshCurrentAndNextYearIfNeeded(this, force = true)
            }
        }

        binding.holidayDataStatusLayout.setOnClickListener {
            if (binding.skipHolidaySwitch.isChecked) {
                ChinaHolidayRemoteUpdater.refreshCurrentAndNextYearIfNeeded(this, force = true)
                "正在刷新节假日数据".show(context)
            } else {
                "开启跳过休息日后会自动刷新节假日数据".show(context)
            }
        }

        binding.minuteRangeLayout.setOnClickListener {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置随机时间范围")
                .setHintMessage("请输入整数，如：30")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        if (value.isNumber()) {
                            updateRandomMinuteRange(value.toInt())
                        } else {
                            "直接输入整数时间即可".show(context)
                        }
                    }

                    override fun onCancelClick() {}
                }).build().show()
        }

        binding.outputLayout.setOnClickListener {
            val exportData = ExportDataModel()

            val taskBeans = DatabaseWrapper.loadAllTask()
            if (taskBeans.isNotEmpty()) {
                exportData.tasks = taskBeans
            } else {
                exportData.tasks = ArrayList<DailyTaskBean>()
            }

            val title = SaveKeyValues.getValue(Constant.MESSAGE_TITLE_KEY, "打卡结果通知") as String
            exportData.messageTitle = title

            val key = SaveKeyValues.getValue(Constant.WX_WEB_HOOK_KEY, "") as String
            exportData.wxKey = key

            exportData.emailConfig = DatabaseWrapper.loadLatestEmailConfig() ?: EmailConfigBean()

            val isDetectGesture = SaveKeyValues.getValue(
                Constant.GESTURE_DETECTOR_KEY, false
            ) as Boolean
            exportData.isDetectGesture = isDetectGesture

            val isBackToHome = SaveKeyValues.getValue(
                Constant.BACK_TO_HOME_KEY, false
            ) as Boolean
            exportData.isBackToHome = isBackToHome

            val hour = SaveKeyValues.getValue(
                Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
            ) as Int
            exportData.resetTime = hour

            val time = SaveKeyValues.getValue(
                Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
            ) as Int
            exportData.overTime = time

            val command = SaveKeyValues.getValue(Constant.TASK_COMMAND_KEY, "打卡") as String
            exportData.command = command

            exportData.isAutoStart = SaveKeyValues.getValue(
                Constant.TASK_AUTO_START_KEY, true
            ) as Boolean

            exportData.isRandomTime = SaveKeyValues.getValue(
                Constant.RANDOM_TIME_KEY, true
            ) as Boolean

            exportData.isSkipChinaHoliday = SaveKeyValues.getValue(
                Constant.SKIP_CHINA_HOLIDAY_KEY, false
            ) as Boolean

            exportData.isPowerSaveMode = SaveKeyValues.getValue(
                Constant.POWER_SAVE_MODE_KEY, false
            ) as Boolean

            exportData.lowBatteryReminder = SaveKeyValues.getValue(
                Constant.LOW_BATTERY_REMINDER_KEY, true
            ) as Boolean

            val value = SaveKeyValues.getValue(Constant.RANDOM_MINUTE_RANGE_KEY, 5) as Int
            exportData.timeRange = value

            val json = exportData.toJson()
            Log.d(kTag, json)

            // 分享
            BottomActionSheet.Builder()
                .setContext(this)
                .setActionItemTitle(optionArray)
                .setItemTextColor(R.color.theme_color.convertColor(this))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        when (position) {
                            0 -> shareTextTo(Constant.QQ, "QQ", json)
                            1 -> shareTextTo(Constant.WECHAT, "微信", json)
                            2 -> shareTextTo(Constant.TIM, "TIM", json)
                            3 -> shareTextTo(Constant.ZFB, "支付宝", json)
                            4 -> {
                                val cipData = ClipData.newPlainText("TaskConfig", json)
                                clipboard.setPrimaryClip(cipData)
                                "已复制到剪切板".show(context)
                            }
                        }
                    }
                }).build().show()
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun handleApplicationEvent(event: ApplicationEvent.HolidayDataStatusChanged) {
        updateHolidayDataStatus()
    }

    private fun updateHolidayDataStatus() {
        val enabled = binding.skipHolidaySwitch.isChecked
        val status = ChinaHolidayCalendar.getDataStatus()
        val todayAction = when {
            !enabled -> "未开启，不影响任务"
            status.todayInfo.shouldSkip -> "开启后今日会跳过"
            else -> "开启后今日会执行"
        }
        val updatedAt = if (status.updatedAt > 0L) {
            statusTimeFormat.format(Date(status.updatedAt))
        } else {
            "无远程缓存"
        }

        binding.holidayDataSourceView.text = buildString {
            append("状态：")
            append(if (enabled) "已开启" else "未开启")
            append(" · 来源：")
            append(status.source)
        }
        binding.holidayDataCoverageView.text = if (status.hasOfficialAdjustment) {
            "覆盖：${status.year}年 · 节假日${status.holidayCount}天 · 补班${status.workdayCount}天"
        } else {
            "覆盖：${status.year}年未配置官方调休表，仅按周末判断"
        }
        binding.holidayDataTodayView.text =
            "今日：${status.todayInfo.reason} · $todayAction\n更新：$updatedAt"
    }

    private fun setHourByPosition(position: Int) {
        if (position == hourArray.size - 1) {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置重置时间")
                .setHintMessage("直接输入整数时间即可，如：6")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        if (value.isNumber()) {
                            updateResetHour(value.toInt())
                        } else {
                            "直接输入整数时间即可".show(context)
                        }
                    }

                    override fun onCancelClick() {}
                }).build().show()
        } else {
            updateResetHour(hourArray[position].toInt())
        }
    }

    private fun updateResetHour(hour: Int) {
        if (hour !in 0..23) {
            "重置时间必须在0到23点之间".show(context)
            return
        }
        binding.resetTimeView.text = "每天${hour}点"
        setTaskResetTime(hour)
    }

    private fun setTaskResetTime(hour: Int) {
        SaveKeyValues.putValue(Constant.RESET_TIME_KEY, hour)
        // 取消旧 Alarm，注册新时间点的 Alarm
        AlarmScheduler.cancel(this)
        AlarmScheduler.schedule(this, hour)
        // 通知 Service 更新倒计时显示
        EventBus.getDefault().post(ApplicationEvent.SetResetTaskTime)
    }

    private fun setTimeByPosition(position: Int) {
        if (position == timeArray.size - 1) {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置超时时间")
                .setHintMessage("直接输入整数时间即可，如：60")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        if (value.isNumber()) {
                            updateTimeout(value.toInt())
                        } else {
                            "直接输入整数时间即可".show(context)
                        }
                    }

                    override fun onCancelClick() {}
                }).build().show()
        } else {
            updateTimeout(timeArray[position].toInt())
        }
    }

    private fun updateTimeout(time: Int) {
        if (time <= 0) {
            "超时时间必须大于0秒".show(context)
            return
        }
        binding.timeoutTextView.text = "${time}s"
        updateDingDingTimeout(time)
    }

    private fun shareTextTo(packageName: String, appName: String, text: String) {
        if (!isApplicationExist(packageName)) {
            "请先安装${appName}".show(this)
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage(packageName)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            "分享失败".show(this)
        }
    }

    private fun updateDingDingTimeout(time: Int) {
        SaveKeyValues.putValue(Constant.STAY_DD_TIMEOUT_KEY, time)
        // 更新目标应用任务超时时间
        EventBus.getDefault().post(ApplicationEvent.SetTaskOvertime(time))
    }

    private fun updateRandomMinuteRange(value: Int) {
        if (value < 0) {
            "随机时间范围不能小于0分钟".show(context)
            return
        }
        binding.minuteRangeView.text = "${value}分钟"
        SaveKeyValues.putValue(Constant.RANDOM_MINUTE_RANGE_KEY, value)
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
    }
}
