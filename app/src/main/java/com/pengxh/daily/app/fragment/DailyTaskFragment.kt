package com.pengxh.daily.app.fragment

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.github.gzuliyujiang.wheelpicker.widget.TimeWheelLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.R
import com.pengxh.daily.app.adapter.DailyTaskAdapter
import com.pengxh.daily.app.databinding.FragmentDailyTaskBinding
import com.pengxh.daily.app.extensions.backToMainActivity
import com.pengxh.daily.app.extensions.convertToTimeEntity
import com.pengxh.daily.app.extensions.diffCurrent
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.extensions.getTaskIndex
import com.pengxh.daily.app.service.CountDownTimerService
import com.pengxh.daily.app.sqlite.DailyTaskBean
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.TimeKit
import com.pengxh.kt.lite.adapter.NormalRecyclerAdapter
import com.pengxh.kt.lite.base.KotlinBaseFragment
import com.pengxh.kt.lite.divider.RecyclerViewItemOffsets
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.utils.WeakReferenceHandler
import com.pengxh.kt.lite.widget.dialog.AlertControlDialog
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import com.yanzhenjie.recyclerview.OnItemClickListener
import com.yanzhenjie.recyclerview.OnItemLongClickListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class DailyTaskFragment : KotlinBaseFragment<FragmentDailyTaskBinding>(), Handler.Callback {
    private val kTag = "DailyTaskFragment"
    private val weakReferenceHandler by lazy { WeakReferenceHandler(this) }
    private val startTaskCode = 2024120801
    private val startCountDownTimerCode = 2024120802
    private val executeNextTaskCode = 2024120803
    private val completedAllTaskCode = 2024120804
    private val marginOffset by lazy { 16.dp2px(requireContext()) }
    private val gson by lazy { Gson() }
    private val emailManager by lazy { EmailManager(requireContext()) }
    private val dailyTaskHandler = Handler(Looper.getMainLooper())
    private lateinit var dailyTaskAdapter: DailyTaskAdapter
    private var taskBeans = mutableListOf<DailyTaskBean>()
    private var isTaskStarted = false
    private var timeoutTimer: CountDownTimer? = null
    private var resetTaskTimer: CountDownTimer? = null
    private var countDownTimerService: CountDownTimerService? = null
    private var isRefresh = false

    private val broadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.action?.let {
                    when (it) {
                        Constant.BROADCAST_START_DAILY_TASK_ACTION -> startExecuteTask()

                        Constant.BROADCAST_STOP_DAILY_TASK_ACTION -> stopExecuteTask()

                        Constant.BROADCAST_RESET_TASK_ACTION -> {
                            dailyTaskHandler.post(dailyTaskRunnable)
                            startResetTaskTimer()
                        }

                        Constant.BROADCAST_START_COUNT_DOWN_TIMER_ACTION -> {
                            // BroadcastReceiver不适合处理耗时操作，使用Handler处理
                            weakReferenceHandler.sendEmptyMessage(startCountDownTimerCode)
                        }

                        Constant.BROADCAST_CANCEL_COUNT_DOWN_TIMER_ACTION -> {
                            timeoutTimer?.cancel()
                            timeoutTimer = null
                            LogFileManager.writeLog("取消超时定时器，执行下一个任务")
                            weakReferenceHandler.sendEmptyMessage(executeNextTaskCode)
                        }
                    }
                }
            }
        }
    }

    override fun handleMessage(msg: Message): Boolean {
        when (msg.what) {
            startTaskCode -> {
                val index = msg.obj as Int
                val task = taskBeans[index]
                binding.tipsView.text = String.format(
                    Locale.getDefault(), "准备执行第 %d 个任务", index + 1
                )
                binding.tipsView.setTextColor(R.color.theme_color.convertColor(requireContext()))

                val pair = task.diffCurrent()
                dailyTaskAdapter.updateCurrentTaskState(index, pair.first)
                val diff = pair.second
                emailManager.sendEmail(
                    "任务执行通知",
                    "准备执行第 ${index + 1} 个任务，计划时间：${task.time}，实际时间: ${pair.first}",
                    false
                )
                countDownTimerService?.startCountDown(index + 1, diff)
            }

            startCountDownTimerCode -> {
                val time = SaveKeyValues.getValue(
                    Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
                ) as String
                //去掉时间的s
                val timeValue = time.dropLast(1).toInt()
                timeoutTimer = object : CountDownTimer(timeValue * 1000L, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        val tick = millisUntilFinished / 1000
                        val intent = Intent(Constant.BROADCAST_TICK_TIME_ACTION).apply {
                            putExtra("data", "$tick")
                        }
                        requireContext().sendBroadcast(intent)
                    }

                    override fun onFinish() {
                        //如果倒计时结束，那么表明没有收到打卡成功的通知
                        requireContext().backToMainActivity()
                        LogFileManager.writeLog("未收到打卡成功通知，发送异常日志邮件")
                        emailManager.sendEmail(null, "", false)
                    }
                }
                timeoutTimer?.start()
            }

            executeNextTaskCode -> dailyTaskHandler.post(dailyTaskRunnable)

            completedAllTaskCode -> {
                binding.tipsView.text = "当天所有任务已执行完毕"
                binding.tipsView.setTextColor(R.color.ios_green.convertColor(requireContext()))
                dailyTaskAdapter.updateCurrentTaskState(-1)
                dailyTaskHandler.removeCallbacks(dailyTaskRunnable)
                countDownTimerService?.updateDailyTaskState()
            }
        }
        return true
    }

    override fun setupTopBarLayout() {

    }

    override fun observeRequestState() {

    }

    /**
     * 服务绑定
     * */
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CountDownTimerService.LocaleBinder
            countDownTimerService = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {

        }
    }

    override fun initViewBinding(
        inflater: LayoutInflater, container: ViewGroup?
    ): FragmentDailyTaskBinding {
        return FragmentDailyTaskBinding.inflate(inflater, container, false)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun initOnCreate(savedInstanceState: Bundle?) {
        val filter = IntentFilter().apply {
            addAction(Constant.BROADCAST_START_DAILY_TASK_ACTION) // 开始执行每日任务
            addAction(Constant.BROADCAST_STOP_DAILY_TASK_ACTION) // 取消执行每日任务
            addAction(Constant.BROADCAST_RESET_TASK_ACTION) // 重置任务
            addAction(Constant.BROADCAST_START_COUNT_DOWN_TIMER_ACTION) // 开始超时定时器
            addAction(Constant.BROADCAST_CANCEL_COUNT_DOWN_TIMER_ACTION) // 取消超时定时器
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            requireContext().registerReceiver(broadcastReceiver, filter)
        }

        taskBeans = DatabaseWrapper.loadAllTask()
        if (taskBeans.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.emptyView.visibility = View.GONE
        }
        dailyTaskAdapter = DailyTaskAdapter(requireContext(), taskBeans)
        binding.recyclerView.setOnItemClickListener(itemClickListener)
        binding.recyclerView.setOnItemLongClickListener(itemLongClickListener)
        binding.recyclerView.adapter = dailyTaskAdapter
        binding.recyclerView.addItemDecoration(
            RecyclerViewItemOffsets(
                marginOffset, marginOffset shr 1, marginOffset, marginOffset shr 1
            )
        )

        Intent(requireContext(), CountDownTimerService::class.java).apply {
            requireContext().bindService(this, connection, Context.BIND_AUTO_CREATE)
        }

        DailyTaskApplication.get().sharedViewModel.addTaskCode.observe(this) {
            if (it == 1) {
                if (isTaskStarted) {
                    "任务进行中，无法添加".show(requireContext())
                    return@observe
                }

                if (taskBeans.isNotEmpty()) {
                    addTask()
                } else {
                    BottomActionSheet.Builder()
                        .setContext(requireContext())
                        .setActionItemTitle(arrayListOf("添加任务", "导入任务"))
                        .setItemTextColor(R.color.theme_color.convertColor(requireContext()))
                        .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                            override fun onActionItemClick(position: Int) {
                                when (position) {
                                    0 -> addTask()
                                    1 -> importTask()
                                }
                            }
                        }).build().show()
                }
            }
        }
    }

    /**
     * 列表项单击
     * */
    private val itemClickListener = object : OnItemClickListener {
        override fun onItemClick(view: View?, adapterPosition: Int) {
            if (isTaskStarted) {
                "任务进行中，无法修改".show(requireContext())
                return
            }
            val item = taskBeans[adapterPosition]
            val view = layoutInflater.inflate(R.layout.bottom_sheet_layout_select_time, null)
            val dialog = BottomSheetDialog(requireContext())
            dialog.setContentView(view)
            val titleView = view.findViewById<MaterialTextView>(R.id.titleView)
            titleView.text = "修改任务时间"
            val timePicker = view.findViewById<TimeWheelLayout>(R.id.timePicker)
            timePicker.setDefaultValue(item.convertToTimeEntity())
            view.findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
                val time = String.format(
                    Locale.getDefault(),
                    "%02d:%02d:%02d",
                    timePicker.selectedHour,
                    timePicker.selectedMinute,
                    timePicker.selectedSecond
                )
                item.time = time
                DatabaseWrapper.updateTask(item)
                taskBeans = DatabaseWrapper.loadAllTask()
                dailyTaskAdapter.refresh(taskBeans)
                dialog.dismiss()
            }
            dialog.show()
        }
    }

    /**
     * 列表项长按
     * */
    private val itemLongClickListener = object : OnItemLongClickListener {
        override fun onItemLongClick(view: View?, adapterPosition: Int) {
            if (isTaskStarted) {
                "任务进行中，无法删除".show(requireContext())
                return
            }
            AlertControlDialog.Builder()
                .setContext(requireContext())
                .setTitle("删除提示")
                .setMessage("确定要删除这个任务吗")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertControlDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick() {
                        try {
                            val item = taskBeans[adapterPosition]
                            DatabaseWrapper.deleteTask(item)
                            taskBeans.removeAt(adapterPosition)
                            dailyTaskAdapter.refresh(taskBeans)
                            if (taskBeans.isEmpty()) {
                                binding.recyclerView.visibility = View.GONE
                                binding.emptyView.visibility = View.VISIBLE
                            } else {
                                binding.recyclerView.visibility = View.VISIBLE
                                binding.emptyView.visibility = View.GONE
                            }
                        } catch (e: IndexOutOfBoundsException) {
                            e.printStackTrace()
                            "删除失败，请刷新重试".show(requireContext())
                        }
                    }

                    override fun onCancelClick() {

                    }
                }).build().show()
        }
    }

    private val itemComparator = object : NormalRecyclerAdapter.ItemComparator<DailyTaskBean> {
        override fun areItemsTheSame(oldItem: DailyTaskBean, newItem: DailyTaskBean): Boolean {
            return oldItem.id == newItem.id && oldItem.time == newItem.time
        }

        override fun areContentsTheSame(oldItem: DailyTaskBean, newItem: DailyTaskBean): Boolean {
            return oldItem.time == newItem.time
        }
    }

    override fun initEvent() {
        binding.executeTaskButton.setOnClickListener {
            if (isTaskStarted) {
                stopExecuteTask()
            } else {
                startExecuteTask()
            }
        }

        binding.refreshView.setOnRefreshListener {
            isRefresh = true
            lifecycleScope.launch(Dispatchers.Main) {
                val result = withContext(Dispatchers.IO) {
                    DatabaseWrapper.loadAllTask()
                }
                delay(500)
                binding.refreshView.finishRefresh()
                isRefresh = false
                dailyTaskAdapter.refresh(result, itemComparator)
            }
        }

        binding.refreshView.setEnableLoadMore(false)
    }

    /**
     * 启动任务
     * */
    private fun startExecuteTask() {
        if (isTaskStarted) {
            emailManager.sendEmail(
                "启动任务通知",
                "任务启动失败，任务已在运行中，请勿重复启动",
                false
            )
            return
        }

        if (DatabaseWrapper.loadAllTask().isEmpty()) {
            "循环任务启动失败，请先添加任务时间点".show(requireContext())
            return
        }
        LogFileManager.writeLog("开始执行每日任务")
        dailyTaskHandler.post(dailyTaskRunnable)
        startResetTaskTimer()
        isTaskStarted = true
        binding.executeTaskButton.setIconResource(R.mipmap.ic_stop)
        binding.executeTaskButton.setIconTintResource(R.color.red)
        binding.executeTaskButton.text = "停止"
        emailManager.sendEmail("启动任务通知", "任务启动成功，请注意下次打卡时间", false)
    }

    /**
     * 当日串行任务Runnable
     * */
    private val dailyTaskRunnable = Runnable {
        val taskIndex = taskBeans.getTaskIndex()
        if (taskIndex == -1) {
            LogFileManager.writeLog("今日任务已全部执行完毕")
            weakReferenceHandler.sendEmptyMessage(completedAllTaskCode)
            emailManager.sendEmail("任务状态通知", "今日任务已全部执行完毕", false)
        } else {
            LogFileManager.writeLog("执行任务，任务index是: $taskIndex，时间是: ${taskBeans[taskIndex].time}")
            weakReferenceHandler.run {
                val message = obtainMessage()
                message.what = startTaskCode
                message.obj = taskIndex
                sendMessage(message)
            }
        }
    }

    private fun startResetTaskTimer() {
        resetTaskTimer?.cancel()
        val currentDiffSeconds = TimeKit.getResetTaskSeconds()
        resetTaskTimer = object : CountDownTimer(currentDiffSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                binding.repeatTimeView.text = String.format(
                    Locale.getDefault(), "%s后刷新每日任务", seconds.formatTime()
                )
            }

            override fun onFinish() {

            }
        }
        resetTaskTimer?.start()
    }

    private fun stopExecuteTask() {
        if (!isTaskStarted) {
            emailManager.sendEmail("停止任务通知", "任务停止失败，任务已经停止，请勿重复停止", false)
            return
        }
        LogFileManager.writeLog("停止执行每日任务")
        dailyTaskHandler.removeCallbacks(dailyTaskRunnable)
        countDownTimerService?.cancelCountDown()
        dailyTaskAdapter.updateCurrentTaskState(-1)
        resetTaskTimer?.cancel()
        isTaskStarted = false
        binding.repeatTimeView.text = "--秒后刷新每日任务"
        binding.executeTaskButton.setIconResource(R.mipmap.ic_start)
        binding.executeTaskButton.setIconTintResource(R.color.ios_green)
        binding.executeTaskButton.text = "启动"
        binding.tipsView.text = ""
        emailManager.sendEmail("停止任务通知", "任务停止成功，请及时打开下次任务", false)
    }

    private fun addTask() {
        val view = layoutInflater.inflate(R.layout.bottom_sheet_layout_select_time, null)
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(view)
        val titleView = view.findViewById<MaterialTextView>(R.id.titleView)
        titleView.text = "添加任务"
        val timePicker = view.findViewById<TimeWheelLayout>(R.id.timePicker)
        view.findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            val time = String.format(
                Locale.getDefault(),
                "%02d:%02d:%02d",
                timePicker.selectedHour,
                timePicker.selectedMinute,
                timePicker.selectedSecond
            )

            if (DatabaseWrapper.isTaskTimeExist(time)) {
                "任务时间点已存在".show(requireContext())
                return@setOnClickListener
            }
            binding.recyclerView.visibility = View.VISIBLE
            binding.emptyView.visibility = View.GONE
            val bean = DailyTaskBean().apply {
                this.time = time
            }
            DatabaseWrapper.insert(bean)
            taskBeans = DatabaseWrapper.loadAllTask()
            dailyTaskAdapter.refresh(taskBeans)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun importTask() {
        AlertInputDialog.Builder()
            .setContext(requireContext())
            .setTitle("导入任务")
            .setHintMessage("请将导出的任务粘贴到这里")
            .setNegativeButton("取消")
            .setPositiveButton("确定")
            .setOnDialogButtonClickListener(object :
                AlertInputDialog.OnDialogButtonClickListener {
                override fun onConfirmClick(value: String) {
                    val type = object : TypeToken<List<DailyTaskBean>>() {}.type
                    try {
                        val tasks = gson.fromJson<List<DailyTaskBean>>(value, type)
                        for (task in tasks) {
                            if (DatabaseWrapper.isTaskTimeExist(task.time)) {
                                continue
                            }
                            DatabaseWrapper.insert(task)
                        }
                        binding.recyclerView.visibility = View.VISIBLE
                        binding.emptyView.visibility = View.GONE
                        taskBeans = DatabaseWrapper.loadAllTask()
                        dailyTaskAdapter.refresh(taskBeans)
                        "任务导入成功".show(requireContext())
                    } catch (e: JsonSyntaxException) {
                        e.printStackTrace()
                        "导入失败，请确认导入的是正确的任务数据".show(requireContext())
                    }
                }

                override fun onCancelClick() {}
            }).build().show()
    }

    override fun onDestroy() {
        super.onDestroy()
        resetTaskTimer?.cancel()
        resetTaskTimer = null
        requireContext().unregisterReceiver(broadcastReceiver)
    }
}