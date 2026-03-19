package com.pengxh.daily.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityTaskConfigBinding
import com.pengxh.daily.app.model.ExportDataModel
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.sqlite.bean.EmailConfigBean
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.getStatusBarHeight
import com.pengxh.kt.lite.extensions.isNumber
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.toJson
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import org.greenrobot.eventbus.EventBus

class TaskConfigActivity : KotlinBaseActivity<ActivityTaskConfigBinding>() {

    private val kTag = "TaskConfigActivity"
    private val context = this
    private val hourArray = arrayListOf("0", "1", "2", "3", "4", "5", "6", "自定义（单位：时）")
    private val timeArray = arrayListOf("15", "30", "45", "自定义（单位：秒）")
    private val clipboard by lazy { getSystemService(CLIPBOARD_SERVICE) as ClipboardManager }

    override fun initViewBinding(): ActivityTaskConfigBinding {
        return ActivityTaskConfigBinding.inflate(layoutInflater)
    }

    override fun observeRequestState() {

    }

    override fun setupTopBarLayout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) { // 16
            binding.toolbar.setPadding(0, getStatusBarHeight(), 0, 0)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        val hour = SaveKeyValues.getValue(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        ) as Int
        binding.resetTimeView.text = "每天${hour}点"
        val time = SaveKeyValues.getValue(
            Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
        ) as Int
        binding.timeoutTextView.text = "${time}s"
        binding.keyTextView.text = SaveKeyValues.getValue(Constant.TASK_COMMAND_KEY, "打卡") as String
        binding.autoTaskSwitch.isChecked = SaveKeyValues.getValue(
            Constant.TASK_AUTO_START_KEY, true
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
                            binding.minuteRangeView.text = "${value}分钟"
                            SaveKeyValues.putValue(Constant.RANDOM_MINUTE_RANGE_KEY, value.toInt())
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

            val configs = DatabaseWrapper.loadAll()
            if (configs.isNotEmpty()) {
                exportData.emailConfig = configs.last()
            } else {
                exportData.emailConfig = EmailConfigBean()
            }

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

            val value = SaveKeyValues.getValue(Constant.RANDOM_MINUTE_RANGE_KEY, 5) as Int
            exportData.timeRange = value

            val json = exportData.toJson()
            Log.d(kTag, json)

            val cipData = ClipData.newPlainText("TaskConfig", json)
            clipboard.setPrimaryClip(cipData)
            "配置已复制到剪切板".show(context)
        }
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
                            val hour = value.toInt()
                            binding.resetTimeView.text = "每天${hour}点"
                            setTaskResetTime(hour)
                        } else {
                            "直接输入整数时间即可".show(context)
                        }
                    }

                    override fun onCancelClick() {}
                }).build().show()
        } else {
            val hour = hourArray[position].toInt()
            binding.resetTimeView.text = "每天${hour}点"
            setTaskResetTime(hour)
        }
    }

    private fun setTaskResetTime(hour: Int) {
        SaveKeyValues.putValue(Constant.RESET_TIME_KEY, hour)
        // 重新开始重置每日任务计时
        EventBus.getDefault().post(ApplicationEvent.SetResetTaskTime(hour))
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
                            val time = value.toInt()
                            binding.timeoutTextView.text = "${time}s"
                            updateDingDingTimeout(time)
                        } else {
                            "直接输入整数时间即可".show(context)
                        }
                    }

                    override fun onCancelClick() {}
                }).build().show()
        } else {
            val time = timeArray[position].toInt()
            binding.timeoutTextView.text = "${time}s"
            updateDingDingTimeout(time)
        }
    }

    private fun updateDingDingTimeout(time: Int) {
        SaveKeyValues.putValue(Constant.STAY_DD_TIMEOUT_KEY, time)
        // 更新目标应用任务超时时间
        EventBus.getDefault().post(ApplicationEvent.SetTaskOvertime(time))
    }
}