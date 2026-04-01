package com.pengxh.daily.app.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.github.gzuliyujiang.wheelpicker.widget.TimeWheelLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.adapter.DailyTaskAdapter
import com.pengxh.daily.app.databinding.ActivityMainBinding
import com.pengxh.daily.app.extensions.convertToTimeEntity
import com.pengxh.daily.app.service.CountDownTimerService
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.GestureController
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MaskViewController
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.TaskDataManager
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.daily.app.utils.TimeoutTimerManager
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.daily.app.vm.MessageViewModel
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.divider.RecyclerViewItemOffsets
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : KotlinBaseActivity<ActivityMainBinding>(), TaskScheduler.TaskStateListener {

    companion object {
        var isTaskStarted = false
    }

    private val context = this
    private val dateFormat by lazy {
        SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss EEEE", Locale.CHINA)
    }
    private val marginOffset by lazy { 16.dp2px(this) }
    private val permissionContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val taskDataManager by lazy { TaskDataManager() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val messageViewModel by lazy { ViewModelProvider(this)[MessageViewModel::class.java] }
    private val messageDispatcher by lazy { MessageDispatcher(this, messageViewModel) }
    private lateinit var insetsController: WindowInsetsControllerCompat
    private lateinit var maskViewController: MaskViewController
    private lateinit var gestureController: GestureController
    private lateinit var dailyTaskAdapter: DailyTaskAdapter
    private lateinit var taskScheduler: TaskScheduler
    private lateinit var timeoutTimerManager: TimeoutTimerManager
    private var taskBeans = mutableListOf<DailyTaskBean>()
    private var imagePath = ""
    private var hasCaptured = false

    override fun observeRequestState() {

    }

    override fun initViewBinding(): ActivityMainBinding {
        return ActivityMainBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }

        // 显示时间
        mainHandler.post(object : Runnable {
            override fun run() {
                val currentTime = dateFormat.format(Date())
                val parts = currentTime.split(" ")
                binding.toolbar.apply {
                    title = parts[2]
                    subtitle = "${parts[0]} ${parts[1]}"
                }
                mainHandler.postDelayed(this, 1000)
            }
        })

        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_add_task -> {
                    if (taskScheduler.isTaskStarted()) {
                        "任务进行中，无法添加".show(this)
                        return@setOnMenuItemClickListener true
                    }

                    if (taskBeans.isNotEmpty()) {
                        createTask()
                    } else {
                        BottomActionSheet.Builder()
                            .setContext(this)
                            .setActionItemTitle(arrayListOf("添加任务", "导入任务"))
                            .setItemTextColor(R.color.theme_color.convertColor(this))
                            .setOnActionSheetListener(object :
                                BottomActionSheet.OnActionSheetListener {
                                override fun onActionItemClick(position: Int) {
                                    when (position) {
                                        0 -> createTask()
                                        1 -> importTask()
                                    }
                                }
                            }).build().show()
                    }
                }

                R.id.menu_settings -> {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("使用须知")
                        .setMessage("本软件完全免费！仅供内部使用！严禁商用或者用作其他非法用途！\r\n近期发现有人在咸鱼私自倒卖本软件，请勿购买！如有购买，请联系卖家退款！")
                        .setCancelable(false) // 禁止点击外部关闭
                        .setPositiveButton("知道了") { _, _ ->
                            navigatePageTo<SettingsActivity>()
                        }.show()
                }
            }
            true
        }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        EventBus.getDefault().register(this)

        // 显示悬浮窗
        if (Settings.canDrawOverlays(this)) {
            Intent(this, FloatingWindowService::class.java).apply {
                startService(this)
            }
        } else {
            // 悬浮窗权限并显示悬浮窗
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            overlayPermissionLauncher.launch(intent)
        }

        insetsController = WindowCompat.getInsetsController(window, binding.rootView)

        Intent(this, ForegroundRunningService::class.java).apply {
            startForegroundService(this)
        }

        Intent(this, CountDownTimerService::class.java).apply {
            bindService(this, serviceConnection, BIND_AUTO_CREATE)
        }

        val watermark = DailyTask.getWatermarkText()
        binding.contentView.background = WatermarkDrawable(this, watermark)

        // 数据
        taskBeans = DatabaseWrapper.loadAllTask()
        if (taskBeans.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.emptyView.visibility = View.GONE
        }
        dailyTaskAdapter = DailyTaskAdapter(this, taskBeans)
        dailyTaskAdapter.setOnItemClickListener(object : DailyTaskAdapter.OnItemClickListener {
            override fun onItemClick(position: Int) {
                itemClick(position)
            }

            override fun onItemLongClick(position: Int) {
                itemLongClick(position)
            }
        })
        binding.recyclerView.adapter = dailyTaskAdapter
        binding.recyclerView.addItemDecoration(
            RecyclerViewItemOffsets(
                marginOffset, marginOffset shr 1, marginOffset, marginOffset shr 1
            )
        )

        maskViewController = MaskViewController(this, binding, insetsController)
        gestureController = GestureController(this, maskViewController, mainHandler)
        taskScheduler = TaskScheduler(mainHandler, taskBeans, this)
        timeoutTimerManager = TimeoutTimerManager(mainHandler)
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun handleApplicationEvent(event: ApplicationEvent) {
        when (event) {
            is ApplicationEvent.ShowMaskView -> {
                if (!maskViewController.isMaskVisible()) {
                    maskViewController.showMaskView(mainHandler)
                }
            }

            is ApplicationEvent.HideMaskView -> {
                if (maskViewController.isMaskVisible()) {
                    maskViewController.hideMaskView(mainHandler)
                }
            }

            is ApplicationEvent.ResetDailyTask -> {
                taskScheduler.startTask()
            }

            is ApplicationEvent.UpdateResetTickTime -> {
                binding.repeatTimeView.text = event.countDownTime
            }

            is ApplicationEvent.StartDailyTask -> {
                if (taskScheduler.isTaskStarted()) {
                    return
                }
                taskScheduler.startTask()
            }

            is ApplicationEvent.StopDailyTask -> {
                if (!taskScheduler.isTaskStarted()) {
                    return
                }
                taskScheduler.stopTask()
            }

            is ApplicationEvent.GoBackMainActivity -> { // 打卡成功发送的消息，回到主界面
                timeoutTimerManager.cancelTimeoutTimer()
                backToMainActivity()
                taskScheduler.executeNextTask()
            }

            is ApplicationEvent.StartCountdownTime -> {
                if (event.isRemoteCommand) {
                    imagePath = ""
                    // 先跳转到目标应用，等待加载，然后截屏
                    object : CountDownTimer(5000, 1000) {
                        override fun onTick(millisUntilFinished: Long) {
                            val tick = (millisUntilFinished / 1000).toInt()
                            // 更新悬浮窗倒计时
                            EventBus.getDefault().post(ApplicationEvent.UpdateFloatingViewTime(tick))
                            if (tick <= 2 && !hasCaptured) {
                                hasCaptured = true
                                EventBus.getDefault().post(ApplicationEvent.CaptureScreen)
                            }
                        }

                        override fun onFinish() {
                            backToMainActivity()
                            if (imagePath == "") {
                                messageDispatcher.sendMessage(
                                    "截屏状态通知", "截图完成，但是无法获取截图，请手动查看结果"
                                )
                            } else {
                                messageDispatcher.sendAttachmentMessage(
                                    "截屏状态通知", "截图完成，结果请查看附件", imagePath
                                )
                            }
                            hasCaptured = false
                        }
                    }.start()
                } else {
                    timeoutTimerManager.startTimeoutTimer {
                        backToMainActivity()

                        val resultSource =
                            SaveKeyValues.getValue(Constant.RESULT_SOURCE_KEY, 0) as Int
                        if (resultSource == 0) {
                            // 如果倒计时结束，那么表明没有收到打卡成功的通知
                            messageDispatcher.sendMessage("", "")
                        } else {
                            if (imagePath == "") {
                                messageDispatcher.sendMessage(
                                    "", "打卡完成，但是无法获取截图，请手动查看结果"
                                )
                            } else {
                                messageDispatcher.sendAttachmentMessage(
                                    "", "打卡完成，结果请查看附件", imagePath
                                )
                            }
                        }

                        taskScheduler.executeNextTask()
                    }
                }
            }

            is ApplicationEvent.CaptureCompleted -> {
                imagePath = event.imagePath
            }

            else -> {}
        }
    }

    private fun backToMainActivity() {
        if (SaveKeyValues.getValue(Constant.BACK_TO_HOME_KEY, false) as Boolean) {
            //模拟点击Home键
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            startActivity(home)

            lifecycleScope.launch(Dispatchers.IO) {
                delay(2000)
                withContext(Dispatchers.Main) {
                    navigatePageTo<MainActivity>()
                }
            }
        } else {
            navigatePageTo<MainActivity>()
        }
    }

    override fun onTaskStarted() {
        isTaskStarted = true
        binding.executeTaskButton.setIconResource(R.mipmap.ic_stop)
        binding.executeTaskButton.setIconTintResource(R.color.red)
        binding.executeTaskButton.text = "停止"
        messageDispatcher.sendMessage("启动任务通知", "任务启动成功，请注意下次打卡时间")
    }

    override fun onTaskStopped() {
        isTaskStarted = false
        // 重置UI状态
        dailyTaskAdapter.updateCurrentTaskState(-1)
        binding.tipsView.text = ""

        // 重置按钮状态
        binding.executeTaskButton.setIconResource(R.mipmap.ic_start)
        binding.executeTaskButton.setIconTintResource(R.color.ios_green)
        binding.executeTaskButton.text = "启动"
        messageDispatcher.sendMessage("停止任务通知", "任务停止成功，请及时打开下次任务")
    }

    override fun onTaskCompleted() {
        // 任务全部完成
        binding.tipsView.text = "当天所有任务已执行完毕"
        binding.tipsView.setTextColor(R.color.ios_green.convertColor(context))
        dailyTaskAdapter.updateCurrentTaskState(-1)
        messageDispatcher.sendMessage("任务状态通知", "今日任务已全部执行完毕")
    }

    override fun onTaskExecuting(taskIndex: Int, task: DailyTaskBean, realTime: String) {
        // 任务执行中
        binding.tipsView.text = String.format(
            Locale.getDefault(), "准备执行第 %d 个任务", taskIndex
        )
        binding.tipsView.setTextColor(R.color.theme_color.convertColor(context))
        dailyTaskAdapter.updateCurrentTaskState(taskIndex - 1, realTime)

        val content = buildString {
            appendLine("准备执行第 $taskIndex 个任务")
            appendLine("计划时间：${task.time}")
            append("实际时间：$realTime")
        }
        messageDispatcher.sendMessage("任务执行通知", content)
    }

    override fun onTaskExecutionError(message: String) {
        messageDispatcher.sendMessage("任务执行出错通知", message)
    }

    private val overlayPermissionLauncher = registerForActivityResult(permissionContract) {
        if (Settings.canDrawOverlays(this)) {
            Intent(this, FloatingWindowService::class.java).apply {
                startService(this)
            }
        }
    }

    /**
     * 服务绑定
     * */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CountDownTimerService.LocaleBinder
            val serviceInstance = binder.getService()
            taskScheduler.setCountDownTimerService(serviceInstance)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            taskScheduler.setCountDownTimerService(null)
        }
    }

    /**
     * 列表项单击
     * */
    private fun itemClick(position: Int) {
        if (taskScheduler.isTaskStarted()) {
            "任务进行中，无法修改".show(this)
            return
        }
        val item = taskBeans[position]
        val view = layoutInflater.inflate(R.layout.bottom_sheet_layout_select_time, null)
        val dialog = BottomSheetDialog(this)
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

    /**
     * 列表项长按
     * */
    private fun itemLongClick(position: Int) {
        if (taskScheduler.isTaskStarted()) {
            "任务进行中，无法删除".show(this)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("删除任务")
            .setMessage("确定要删除这个任务吗？")
            .setCancelable(false) // 禁止点击外部关闭
            .setPositiveButton("确定") { _, _ ->
                try {
                    val item = taskBeans[position]
                    DatabaseWrapper.deleteTask(item)
                    taskBeans.removeAt(position)

                    if (taskBeans.isEmpty()) {
                        binding.recyclerView.visibility = View.GONE
                        binding.emptyView.visibility = View.VISIBLE
                        dailyTaskAdapter.refresh(taskBeans)
                    } else {
                        dailyTaskAdapter.refresh(taskBeans)
                        binding.recyclerView.visibility = View.VISIBLE
                        binding.emptyView.visibility = View.GONE
                    }
                } catch (e: IndexOutOfBoundsException) {
                    e.printStackTrace()
                }
            }.setNegativeButton("取消", null).show()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            gestureController.onTouchEvent(it)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun initEvent() {
        binding.executeTaskButton.setOnClickListener {
            if (taskScheduler.isTaskStarted()) {
                taskScheduler.stopTask()
            } else {
                if (DatabaseWrapper.loadAllTask().isEmpty()) {
                    "循环任务启动失败，请先添加任务时间点".show(this)
                    return@setOnClickListener
                }
                taskScheduler.startTask()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (maskViewController.isMaskVisible()) {
                maskViewController.hideMaskView(mainHandler)
            } else {
                maskViewController.showMaskView(mainHandler)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun createTask() {
        val view = layoutInflater.inflate(R.layout.bottom_sheet_layout_select_time, null)
        val dialog = BottomSheetDialog(this)
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
                "任务时间点已存在".show(this)
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
            .setContext(this)
            .setTitle("导入任务")
            .setHintMessage("请将导出的任务粘贴到这里")
            .setNegativeButton("取消")
            .setPositiveButton("确定")
            .setOnDialogButtonClickListener(object :
                AlertInputDialog.OnDialogButtonClickListener {
                override fun onConfirmClick(value: String) {
                    when (val result = taskDataManager.importTasks(value)) {
                        is TaskDataManager.ImportResult.Success -> {
                            if (result.count > 0) {
                                binding.recyclerView.visibility = View.VISIBLE
                                binding.emptyView.visibility = View.GONE
                                taskBeans = DatabaseWrapper.loadAllTask()
                                dailyTaskAdapter.refresh(taskBeans)
                            }
                            "任务导入成功".show(context)
                        }

                        is TaskDataManager.ImportResult.Error -> {
                            result.message.show(context)
                        }
                    }
                }

                override fun onCancelClick() {}
            }).build().show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LogFileManager.writeLog("onNewIntent: ${packageName}回到前台")
        if (!maskViewController.isMaskVisible()) {
            maskViewController.showMaskView(mainHandler)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        maskViewController.destroy(mainHandler)
        taskScheduler.destroy()
        timeoutTimerManager.destroy()

        mainHandler.removeCallbacksAndMessages(null)

        EventBus.getDefault().unregister(this)
        try {
            unbindService(serviceConnection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}