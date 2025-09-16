package com.pengxh.daily.app.fragment

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
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
import com.pengxh.daily.app.bean.DailyTaskBean
import com.pengxh.daily.app.databinding.FragmentDailyTaskBinding
import com.pengxh.daily.app.extensions.backToMainActivity
import com.pengxh.daily.app.extensions.convertToTimeEntity
import com.pengxh.daily.app.extensions.diffCurrent
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.extensions.getTaskIndex
import com.pengxh.daily.app.extensions.sendEmail
import com.pengxh.daily.app.service.CountDownTimerService
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DatabaseWrapper
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
import java.util.concurrent.atomic.AtomicInteger

class DailyTaskFragment : KotlinBaseFragment<FragmentDailyTaskBinding>(), Handler.Callback {

    companion object {
        var weakReferenceHandler: WeakReferenceHandler? = null
    }

    private val kTag = "DailyTaskFragment"
    private val marginOffset by lazy { 16.dp2px(requireContext()) }
    private val gson by lazy { Gson() }
    private val repeatTaskHandler = Handler(Looper.getMainLooper())
    private val dailyTaskHandler = Handler(Looper.getMainLooper())
    private lateinit var dailyTaskAdapter: DailyTaskAdapter
    private var taskBeans: MutableList<DailyTaskBean> = ArrayList()
    private var diffSeconds = AtomicInteger(0)
    private var isTaskStarted = false
    private var timeoutTimer: CountDownTimer? = null
    private var countDownTimerService: CountDownTimerService? = null
    private var isRefresh = false
    private var isRemoteTask = false
    private var serviceIntent: Intent? = null

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

    override fun initOnCreate(savedInstanceState: Bundle?) {
        weakReferenceHandler = WeakReferenceHandler(this)
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

        DailyTaskApplication.get().sharedViewModel.addTaskCode.observe(viewLifecycleOwner) {
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
            if (!isTaskStarted) {
                startExecuteTask(false)
            } else {
                stopExecuteTask(false)
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

    private fun startExecuteTask(isRemote: Boolean) {
        isRemoteTask = isRemote
        if (Settings.canDrawOverlays(requireContext())) {
            startFloatingWindowService()
        } else {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            overlayPermissionLauncher.launch(intent)
        }
    }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(requireContext())) {
                startFloatingWindowService()
            }
        }

    private fun startFloatingWindowService() {
        if (serviceIntent == null) {
            serviceIntent = Intent(requireContext(), FloatingWindowService::class.java)
        }
        requireContext().startService(serviceIntent)
        if (DatabaseWrapper.loadAllTask().isEmpty()) {
            "循环任务启动失败，请先添加任务时间点".sendEmail(
                requireContext(), "启动循环任务通知", false
            )
            return
        }
        diffSeconds.set(TimeKit.getResetTaskSeconds())
        repeatTaskHandler.post(repeatTaskRunnable)
        Log.d(kTag, "startExecuteTask: 开启周期任务Runnable")
        executeDailyTask()
        isTaskStarted = true
        binding.executeTaskButton.setIconResource(R.mipmap.ic_stop)
        binding.executeTaskButton.setIconTintResource(R.color.red)
        binding.executeTaskButton.text = "停止"
        if (isRemoteTask) {
            "循环任务启动成功，请注意下次打卡时间".sendEmail(
                requireContext(), "启动循环任务通知", false
            )
        }
    }

    private fun stopExecuteTask(isRemote: Boolean) {
        repeatTaskHandler.removeCallbacks(repeatTaskRunnable)
        Log.d(kTag, "stopExecuteTask: 取消周期任务Runnable")
        countDownTimerService?.cancelCountDown()
        isTaskStarted = false
        binding.repeatTimeView.text = "--秒后刷新每日任务"
        binding.executeTaskButton.setIconResource(R.mipmap.ic_start)
        binding.executeTaskButton.setIconTintResource(R.color.ios_green)
        binding.executeTaskButton.text = "启动"
        binding.tipsView.text = ""
        dailyTaskAdapter.updateCurrentTaskState(-1)
        if (isRemote) {
            "循环任务停止成功，请及时打开下次任务".sendEmail(
                requireContext(), "暂停循环任务通知", false
            )
        }
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

    /**
     * 循环任务Runnable
     * */
    private val repeatTaskRunnable = object : Runnable {
        override fun run() {
            val currentDiffSeconds = diffSeconds.decrementAndGet()
            if (currentDiffSeconds > 0) {
                viewLifecycleOwner.lifecycleScope.launch {
                    binding.repeatTimeView.text = String.format(
                        Locale.getDefault(), "%s后刷新每日任务", currentDiffSeconds.formatTime()
                    )
                }
                repeatTaskHandler.postDelayed(this, 1000)
            } else {
                // 刷新任务，并重启repeatTaskRunnable
                diffSeconds.set(TimeKit.getResetTaskSeconds())
                // 确保移除旧的回调
                repeatTaskHandler.removeCallbacks(this)
                repeatTaskHandler.post(this)
                Log.d(kTag, "run: 零点，刷新任务，并重新执行repeatTaskRunnable")
                executeDailyTask()
            }
        }
    }

    private fun executeDailyTask() {
        Log.d(kTag, "executeDailyTask: 执行周期任务")
        dailyTaskHandler.post(dailyTaskRunnable)
    }

    /**
     * 当日串行任务Runnable
     * */
    private val dailyTaskRunnable = Runnable {
        val taskIndex = taskBeans.getTaskIndex()
        Log.d(kTag, "任务index是: $taskIndex")
        if (taskIndex == -1) {
            weakReferenceHandler?.sendEmptyMessage(Constant.COMPLETED_ALL_TASK_CODE)
        } else {
            weakReferenceHandler?.let {
                val message = it.obtainMessage()
                message.what = Constant.START_TASK_CODE
                message.obj = taskIndex
                it.sendMessage(message)
            }
        }
    }

    override fun handleMessage(msg: Message): Boolean {
        when (msg.what) {
            Constant.START_DAILY_TASK_CODE -> startExecuteTask(true)

            Constant.START_TASK_CODE -> {
                val index = msg.obj as Int
                val task = taskBeans[index]
                binding.tipsView.text = String.format(
                    Locale.getDefault(), "准备执行第 %d 个任务", index + 1
                )
                binding.tipsView.setTextColor(R.color.theme_color.convertColor(requireContext()))

                val pair = task.diffCurrent()
                dailyTaskAdapter.updateCurrentTaskState(index, pair.first)
                val diff = pair.second
                Log.d(kTag, "任务时间差是: $diff 秒")
                "准备执行第 ${index + 1} 个任务，计划时间：${task.time}，实际时间: ${pair.first}".sendEmail(
                    requireContext(), "任务执行通知", false
                )
                countDownTimerService?.startCountDown(index + 1, diff)
            }

            Constant.EXECUTE_NEXT_TASK_CODE -> {
                dailyTaskHandler.post(dailyTaskRunnable)
            }

            Constant.START_COUNT_DOWN_TIMER_CODE -> {
                Log.d(kTag, "开始超时倒计时")
                val time = SaveKeyValues.getValue(
                    Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
                ) as String
                //去掉时间的s
                val timeValue = time.dropLast(1).toInt()
                timeoutTimer = object : CountDownTimer(timeValue * 1000L, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        val tick = millisUntilFinished / 1000
                        FloatingWindowService.weakReferenceHandler?.let {
                            val message = it.obtainMessage()
                            message.what = Constant.TICK_TIME_CODE
                            message.obj = tick
                            it.sendMessage(message)
                        }
                    }

                    override fun onFinish() {
                        //如果倒计时结束，那么表明没有收到打卡成功的通知
                        requireContext().backToMainActivity()
                        "未监听到打卡通知，发送异常日志邮件，请注意查收".show(requireContext())
                        "".sendEmail(requireContext(), null, false)
                    }
                }
                timeoutTimer?.start()
            }

            Constant.CANCEL_COUNT_DOWN_TIMER_CODE -> {
                timeoutTimer?.cancel()
                timeoutTimer = null
                Log.d(kTag, "取消超时定时器，执行下一个任务")
                weakReferenceHandler?.sendEmptyMessage(Constant.EXECUTE_NEXT_TASK_CODE)
            }

            Constant.COMPLETED_ALL_TASK_CODE -> {
                binding.tipsView.text = "当天所有任务已执行完毕"
                binding.tipsView.setTextColor(R.color.ios_green.convertColor(requireContext()))
                dailyTaskAdapter.updateCurrentTaskState(-1)
                dailyTaskHandler.removeCallbacks(dailyTaskRunnable)
                countDownTimerService?.updateDailyTaskState()
            }

            Constant.STOP_DAILY_TASK_CODE -> stopExecuteTask(true)
        }
        return true
    }
}