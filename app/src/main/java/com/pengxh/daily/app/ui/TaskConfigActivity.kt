package com.pengxh.daily.app.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.pengxh.daily.app.R
import com.pengxh.daily.app.adapter.WorkdayAdapter
import com.pengxh.daily.app.base.KotlinBaseActivity
import com.pengxh.daily.app.databinding.ActivityTaskConfigBinding
import com.pengxh.daily.app.extensions.show
import com.pengxh.daily.app.extensions.showActionSheet
import com.pengxh.daily.app.extensions.showCustomDialog
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.utils.AppConfig
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.CustomWorkdayManager
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.daily.app.widget.DeletableEditText
import com.pengxh.daily.app.widget.SettingItemView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.DayOfWeek

class TaskConfigActivity : KotlinBaseActivity<ActivityTaskConfigBinding>() {

    private val kTag = "TaskConfigActivity"
    private val context = this@TaskConfigActivity

    override fun initViewBinding(): ActivityTaskConfigBinding {
        return ActivityTaskConfigBinding.inflate(layoutInflater)
    }

    override fun observeRequestState() {

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
        val hour = AppConfig.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        binding.resetTimeItem.rightTextView.text = "每天 $hour 点"

        val time = AppConfig.loadInt(Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME)
        binding.timeoutItem.rightTextView.text = "$time 秒"

        binding.keyItem.rightTextView.text =
            AppConfig.loadString(Constant.REMOTE_COMMAND_KEY, "打卡")

        updateCustomWorkdaySummary(CustomWorkdayManager.loadWorkdays())

        // 循环任务状态仅能通过远程指令修改，开关只作展示
        binding.autoTaskItem.switchView.isClickable = false
        binding.autoTaskItem.switchView.isFocusable = false
        binding.autoTaskItem.switchView.isChecked =
            AppConfig.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)

        binding.skipHolidayItem.switchView.isChecked =
            AppConfig.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)

        val needRandom = AppConfig.loadBoolean(Constant.RANDOM_TIME_KEY, true)
        binding.randomTimeItem.switchView.isChecked = needRandom
        if (needRandom) {
            binding.minuteRangeItem.visibility = View.VISIBLE
            val value = AppConfig.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)
            binding.minuteRangeItem.rightTextView.text = "$value 分钟"
        } else {
            binding.minuteRangeItem.visibility = View.GONE
        }

        // 运行中排程已按当日 flag 计算，中途改工作日会引入额外判断，故直接锁定入口
        lifecycleScope.launch {
            TaskScheduler.isRunning.collectLatest { running ->
                binding.workdayLayout.apply {
                    isEnabled = !running
                    alpha = if (running) 0.5f else 1f
                }
            }
        }
    }

    override fun initEvent() {
        binding.resetTimeItem.setOnClickListener {
            showActionSheet(R.layout.sheet_set_reset_time) { view, dialog ->
                val optionsLayout = view.findViewById<LinearLayout>(R.id.optionsLayout)
                (0..6).forEach { hour ->
                    val item = SettingItemView(this).apply {
                        title = "每天 $hour 点"
                        setOnClickListener {
                            updateResetHour(hour)
                            dialog.dismiss()
                        }
                    }
                    optionsLayout.addView(item)
                }

                // 自定义入口：点开输入框
                val customItem = SettingItemView(context).apply {
                    title = "自定义（单位：时）"
                    setOnClickListener {
                        dialog.dismiss()
                        showCustomDialog(R.layout.dialog_set_reset_time) { view, dialog ->
                            val inputView = view.findViewById<DeletableEditText>(R.id.inputView)
                            view.findViewById<MaterialButton>(R.id.confirmButton)
                                .setOnClickListener {
                                if (inputView.text.isNullOrBlank()) {
                                    inputView.shakeIfEmpty()
                                    "请输入重置时间".show(context)
                                    return@setOnClickListener
                                }
                                updateResetHour(inputView.text.toString().toInt())
                                dialog.dismiss()
                            }
                            view.findViewById<MaterialButton>(R.id.cancelButton)
                                .setOnClickListener {
                                dialog.dismiss()
                            }
                        }
                    }
                }
                optionsLayout.addView(customItem)
            }
        }

        binding.timeoutItem.setOnClickListener {
            showActionSheet(R.layout.sheet_set_over_time) { view, dialog ->
                val optionsLayout = view.findViewById<LinearLayout>(R.id.optionsLayout)
                listOf(15, 30, 45, 60).forEach { seconds ->
                    val item = SettingItemView(this).apply {
                        title = "$seconds 秒"
                        setOnClickListener {
                            updateTimeout(seconds)
                            dialog.dismiss()
                        }
                    }
                    optionsLayout.addView(item)
                }

                // 自定义入口：点开输入框
                val customItem = SettingItemView(context).apply {
                    title = "自定义（单位：秒）"
                    setOnClickListener {
                        dialog.dismiss()
                        showCustomDialog(R.layout.dialog_set_custom_time) { view, dialog ->
                            val inputView = view.findViewById<DeletableEditText>(R.id.inputView)
                            view.findViewById<MaterialButton>(R.id.confirmButton)
                                .setOnClickListener {
                                if (inputView.text.isNullOrBlank()) {
                                    inputView.shakeIfEmpty()
                                    "请输入超时时间".show(context)
                                    return@setOnClickListener
                                }
                                updateTimeout(inputView.text.toString().toInt())
                                dialog.dismiss()
                            }
                            view.findViewById<MaterialButton>(R.id.cancelButton)
                                .setOnClickListener {
                                dialog.dismiss()
                            }
                        }
                    }
                }
                optionsLayout.addView(customItem)
            }
        }

        binding.keyItem.setOnClickListener {
            showCustomDialog(R.layout.dialog_set_remote_command) { view, dialog ->
                val inputView = view.findViewById<DeletableEditText>(R.id.inputView)
                inputView.setText(AppConfig.loadString(Constant.REMOTE_COMMAND_KEY, "打卡"))
                view.findViewById<MaterialButton>(R.id.confirmButton).setOnClickListener {
                    if (inputView.text.isNullOrBlank()) {
                        inputView.shakeIfEmpty()
                        "请输入打卡口令".show(context)
                        return@setOnClickListener
                    }
                    AppConfig.saveString(Constant.REMOTE_COMMAND_KEY, inputView.text.toString())
                    binding.keyItem.rightTextView.text = inputView.text.toString()
                    dialog.dismiss()
                }
                view.findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener {
                    dialog.dismiss()
                }
            }
        }

        binding.workdayLayout.setOnClickListener {
            if (TaskScheduler.isRunning()) {
                "任务进行中，无法修改工作日".show(context)
                return@setOnClickListener
            }
            showActionSheet(R.layout.sheet_select_workday) { view, dialog ->
                val selected = CustomWorkdayManager.loadWorkdays().toMutableSet()
                val adapter = WorkdayAdapter(selected)
                view.findViewById<RecyclerView>(R.id.recyclerView).adapter = adapter

                view.findViewById<View>(R.id.confirmButton).setOnClickListener {
                    // 二次校验：对话框打开期间任务可能被启动
                    if (TaskScheduler.isRunning()) {
                        "任务进行中，无法修改工作日".show(this)
                        return@setOnClickListener
                    }

                    if (selected.isEmpty()) {
                        "至少保留一天为工作日".show(context)
                        return@setOnClickListener
                    }
                    CustomWorkdayManager.saveWorkdays(selected)
                    updateCustomWorkdaySummary(selected)
                    dialog.dismiss()
                }
                view.findViewById<View>(R.id.cancelButton).setOnClickListener { dialog.dismiss() }
            }
        }

        binding.randomTimeItem.switchView.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.saveBoolean(Constant.RANDOM_TIME_KEY, isChecked)
            if (isChecked) {
                binding.minuteRangeItem.visibility = View.VISIBLE
                val value =
                    AppConfig.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)
                binding.minuteRangeItem.rightTextView.text = "$value 分钟"
            } else {
                binding.minuteRangeItem.visibility = View.GONE
            }
        }

        binding.skipHolidayItem.switchView.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.saveBoolean(Constant.SKIP_HOLIDAY_KEY, isChecked)
        }

        binding.minuteRangeItem.setOnClickListener {
            showCustomDialog(R.layout.dialog_set_time_range) { view, dialog ->
                val inputView = view.findViewById<DeletableEditText>(R.id.inputView)
                view.findViewById<MaterialButton>(R.id.confirmButton).setOnClickListener {
                    if (inputView.text.isNullOrBlank()) {
                        inputView.shakeIfEmpty()
                        "请输入随机时间".show(context)
                        return@setOnClickListener
                    }
                    updateRandomMinuteRange(inputView.text.toString().toInt())
                    dialog.dismiss()
                }
                view.findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener {
                    dialog.dismiss()
                }
            }
        }
    }

    private fun updateCustomWorkdaySummary(workdays: Set<DayOfWeek>) {
        binding.workdayValueView.text = CustomWorkdayManager.formatWorkdays(workdays)
    }

    private fun setTaskResetTime(hour: Int) {
        AppConfig.saveInt(Constant.RESET_TIME_KEY, hour)
        // 通知 Service 更新倒计时显示
        ForegroundRunningService.emitResetTaskTime()
    }

    private fun updateResetHour(hour: Int) {
        if (hour !in 0..23) {
            "重置时间必须在0到23点之间".show(this)
            return
        }
        binding.resetTimeItem.rightTextView.text = "每天 $hour 点"
        setTaskResetTime(hour)
    }

    private fun updateTimeout(time: Int) {
        if (time <= 0) {
            "超时时间必须大于0秒".show(this)
            return
        }
        binding.timeoutItem.rightTextView.text = "$time 秒"
        AppConfig.saveInt(Constant.STAY_OVERTIME_KEY, time)
        FloatingWindowController.setOvertime(time)
    }

    private fun updateRandomMinuteRange(value: Int) {
        if (value < 0) {
            "随机时间不能小于0分钟".show(this)
            return
        }
        binding.minuteRangeItem.rightTextView.text = "$value 分钟"
        AppConfig.saveInt(Constant.TIME_RANGE_KEY, value)
    }
}
