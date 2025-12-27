package com.pengxh.daily.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityTaskConfigBinding
import com.pengxh.daily.app.event.UpdateDingDingTimeoutEvent
import com.pengxh.daily.app.event.UpdateTaskResetTimeEvent
import com.pengxh.daily.app.extensions.initImmersionBar
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.widgets.TaskMessageDialog
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.isNumber
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.TitleBarView
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import org.greenrobot.eventbus.EventBus

class TaskConfigActivity : KotlinBaseActivity<ActivityTaskConfigBinding>() {

    private val kTag = "TaskConfigActivity"
    private val context = this
    private val hourArray = arrayListOf("0", "1", "2", "3", "4", "5", "6", "自定义（单位：时）")
    private val timeArray = arrayListOf("15", "30", "45", "自定义（单位：秒）")
    private val clipboard by lazy { getSystemService(CLIPBOARD_SERVICE) as ClipboardManager }

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
                .setPositiveButton("确定").setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        SaveKeyValues.putValue(Constant.TASK_NAME_KEY, value)
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
                .setPositiveButton("确定").setOnDialogButtonClickListener(object :
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
            val taskBeans = DatabaseWrapper.loadAllTask()

            if (taskBeans.isEmpty()) {
                "没有任务可以导出".show(this)
                return@setOnClickListener
            }

            TaskMessageDialog.Builder().setContext(this).setTasks(taskBeans)
                .setOnDialogButtonClickListener(object :
                    TaskMessageDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(taskValue: String) {
                        val cipData = ClipData.newPlainText("DailyTask", taskValue)
                        clipboard.setPrimaryClip(cipData)
                        "任务已复制到剪切板".show(context)
                    }
                }).build().show()
        }
    }

    private fun setHourByPosition(position: Int) {
        var hour = 0
        if (position == hourArray.size - 1) {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置重置时间")
                .setHintMessage("直接输入整数时间即可，如：6")
                .setNegativeButton("取消")
                .setPositiveButton("确定").setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        if (value.isNumber()) {
                            hour = value.toInt()
                            binding.resetTimeView.text = "每天${hour}点"
                        } else {
                            "直接输入整数时间即可".show(context)
                        }
                    }

                    override fun onCancelClick() {}
                }).build().show()
        } else {
            hour = hourArray[position].toInt()
            binding.resetTimeView.text = "每天${hour}点"
        }

        SaveKeyValues.putValue(Constant.RESET_TIME_KEY, hour)
        // 重新开始重置每日任务计时
        EventBus.getDefault().post(UpdateTaskResetTimeEvent(hour))
    }

    private fun setTimeByPosition(position: Int) {
        var time = 15
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
                            time = value.toInt()
                            binding.timeoutTextView.text = "${time}s"
                        } else {
                            "直接输入整数时间即可".show(context)
                        }
                    }

                    override fun onCancelClick() {}
                }).build().show()
        } else {
            time = timeArray[position].toInt()
            binding.timeoutTextView.text = "${time}s"
        }

        SaveKeyValues.putValue(Constant.STAY_DD_TIMEOUT_KEY, time)
        // 更新钉钉任务超时时间
        EventBus.getDefault().post(UpdateDingDingTimeoutEvent(time))
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
        binding.keyTextView.text = SaveKeyValues.getValue(Constant.TASK_NAME_KEY, "打卡") as String
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

    override fun initViewBinding(): ActivityTaskConfigBinding {
        return ActivityTaskConfigBinding.inflate(layoutInflater)
    }

    override fun observeRequestState() {

    }

    override fun setupTopBarLayout() {
        binding.rootView.initImmersionBar(this, true, R.color.white)
        binding.titleView.setOnClickListener(object : TitleBarView.OnClickListener {
            override fun onLeftClick() {
                finish()
            }

            override fun onRightClick() {

            }
        })
    }
}