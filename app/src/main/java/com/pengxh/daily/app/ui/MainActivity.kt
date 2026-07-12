package com.pengxh.daily.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.ChinaHolidayManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.GestureController
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MaskViewController
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.utils.SchedulerState
import com.pengxh.daily.app.utils.TaskDataManager
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.daily.app.vm.MessageViewModel
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.divider.RecyclerViewItemBorder
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Date
import java.util.Locale

class MainActivity : KotlinBaseActivity<ActivityMainBinding>(),
    NotificationMonitorService.MonitorCallback {

    companion object {
        @Volatile
        var isTaskStarted = false
    }

    private val context by lazy { this }
    private val dateTimeFormat by lazy {
        SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss EEEE", Locale.CHINA)
    }
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA) }
    private val marginOffset by lazy { 16.dp2px(this) }
    private val permissionContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val taskDataManager by lazy { TaskDataManager() }
    private val insetsController by lazy {
        WindowCompat.getInsetsController(window, binding.rootView)
    }
    private val messageViewModel by lazy { ViewModelProvider(this)[MessageViewModel::class.java] }
    private val messageDispatcher by lazy { MessageDispatcher(this, messageViewModel) }
    private val gestureController by lazy { GestureController(this, maskViewController) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val maskViewController by lazy { MaskViewController(this, binding, insetsController) }
    private var taskBeans = mutableListOf<DailyTaskBean>()
    private val dailyTaskAdapter by lazy {
        DailyTaskAdapter(taskBeans).apply {
            setOnItemClickListener(object : DailyTaskAdapter.OnItemClickListener {
                override fun onItemClick(position: Int) {
                    itemClick(position)
                }

                override fun onItemLongClick(position: Int) {
                    itemLongClick(position)
                }
            })
        }
    }
    private var imagePath = ""
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            val currentTime = dateTimeFormat.format(Date())
            val parts = currentTime.split(" ")
            val now = LocalDate.now()
            val flag = when {
                // 法定节假日（如国庆、春节等，含调休放假，不含普通周末）
                ChinaHolidayManager.isHoliday(now) -> "节假日"

                // 调休补班日（如周末上班补假期）
                ChinaHolidayManager.isWorkday(now) -> "补班日"

                // 普通日期：周末/工作日
                else -> {
                    when (now.dayOfWeek) {
                        DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> "休息日"
                        else -> "工作日"
                    }
                }
            }
            binding.toolbar.apply {
                title = "${parts[2]}（$flag）"
                subtitle = "${parts[0]} ${parts[1]}"
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

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
        mainHandler.post(timeUpdateRunnable)

        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_add_task -> {
                    if (isTaskStarted) {
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

    private val overlayPermissionLauncher = registerForActivityResult(permissionContract) {
        if (Settings.canDrawOverlays(this)) {
            Intent(this, FloatingWindowService::class.java).apply {
                startService(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Settings.canDrawOverlays(this)) {
            "悬浮窗权限未开启，部分功能可能无法正常使用".show(this)
        }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
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

        binding.recyclerView.adapter = dailyTaskAdapter
        binding.recyclerView.addItemDecoration(
            RecyclerViewItemBorder(
                marginOffset, marginOffset shr 1, marginOffset, marginOffset shr 1
            )
        )

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

        // 启动常驻前台服务——保活+任务重置+托管 TaskScheduler 协程作用域
        Intent(this, ForegroundRunningService::class.java).apply {
            startForegroundService(this)
        }

        // 注册监听服务回调
        NotificationMonitorService.monitorCallback = this

        // ====== 观察 TaskScheduler 状态，驱动 UI 更新（替代原 TaskStateListener）======
        lifecycleScope.launch {
            TaskScheduler.state.collectLatest { state ->
                handleSchedulerState(state)
            }
        }

        EventBus.getDefault().register(this)
        // 处理 Alarm 触发时 Activity 未注册导致的 ResetDailyTask 事件丢失
        val stickyReset =
            EventBus.getDefault().getStickyEvent(ApplicationEvent.ResetDailyTask::class.java)
        if (stickyReset != null) {
            EventBus.getDefault().removeStickyEvent(stickyReset)
            TaskScheduler.startTask(this)
        }

        // 检查是否需要执行错过的重置
        checkMissedReset()
    }

    /**
     * 根据 TaskScheduler 的 StateFlow 状态更新 UI（替代原 TaskStateListener 的 7 个回调）
     */
    private fun handleSchedulerState(state: SchedulerState) {
        when (state) {
            is SchedulerState.Idle -> {
                // 由 stopTask() 触发，重置 UI
                isTaskStarted = false
                dailyTaskAdapter.updateCurrentTaskState(-1)
                binding.tipsView.text = ""
                resetExecuteButton()
            }

            is SchedulerState.Skipped -> {
                isTaskStarted = true
                binding.executeTaskButton.setIconResource(R.mipmap.ic_stop)
                binding.executeTaskButton.setIconTintResource(R.color.red)
                binding.executeTaskButton.text = "停止"
                binding.tipsView.text = "今日为周末，跳过任务"
                binding.tipsView.setTextColor(R.color.ios_green.convertColor(this))
                messageDispatcher.sendMessage(
                    "启动任务通知", "当前为节假日，任务已自动跳过，请注意下次打卡时间"
                )
            }

            is SchedulerState.Executing -> {
                isTaskStarted = true
                // 首次进入执行态：更新按钮
                if (binding.executeTaskButton.text != "停止") {
                    binding.executeTaskButton.setIconResource(R.mipmap.ic_stop)
                    binding.executeTaskButton.setIconTintResource(R.color.red)
                    binding.executeTaskButton.text = "停止"
                    messageDispatcher.sendMessage("启动任务通知", "任务启动成功，请注意下次打卡时间")
                }
                // 更新 Tips 和 Adapter
                binding.tipsView.text = String.format(
                    Locale.getDefault(), "准备执行第 %d 个任务", state.taskIndex
                )
                binding.tipsView.setTextColor(R.color.theme_color.convertColor(this))
                dailyTaskAdapter.updateCurrentTaskState(state.taskIndex - 1, state.actualTime)

                val content = buildString {
                    appendLine("准备执行第 ${state.taskIndex} 个任务")
                    appendLine("计划时间：${state.task.time}")
                    append("实际时间：${state.actualTime}")
                }
                messageDispatcher.sendMessage("任务执行通知", content)

                // 导航回主界面（任务在后台执行，需要切回来让用户看到）
                backToMainActivity()
            }

            is SchedulerState.Completed -> {
                dailyTaskAdapter.updateCurrentTaskState(-1)
                binding.tipsView.text = "当天所有任务已执行完毕"
                binding.tipsView.setTextColor(R.color.ios_green.convertColor(this))
                messageDispatcher.sendMessage("任务状态通知", "今日任务已全部执行完毕")
            }
        }
    }

    private fun checkMissedReset() {
        val lastResetDate = SaveKeyValues.loadString(Constant.LAST_RESET_DATE_KEY, "")
        val today = dateFormat.format(Date())

        // 今天已重置，跳过（防止重复执行）
        if (lastResetDate == today) {
            return
        }

        // 今天还未重置，执行重置（覆盖 Alarm 未触发的场景）
        LogFileManager.writeLog("检测到今日尚未重置，执行重置操作")
        SaveKeyValues.saveString(Constant.LAST_RESET_DATE_KEY, today)

        if (SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)) {
            TaskScheduler.startTask(this)
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun handleApplicationEvent(event: ApplicationEvent) {
        when (event) {
            is ApplicationEvent.ResetDailyTask -> {
                EventBus.getDefault().removeStickyEvent(ApplicationEvent.ResetDailyTask)
                TaskScheduler.startTask(this)
            }

            is ApplicationEvent.UpdateResetTickTime -> {
                binding.repeatTimeView.text = event.countDownTime
            }

            is ApplicationEvent.StopDailyTask -> doStopTask()

            is ApplicationEvent.CaptureCompleted -> {
                imagePath = event.imagePath
            }

            is ApplicationEvent.ProjectionDestroyed -> {
                "截屏服务已停止，已切换到通知模式".show(this)
                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
            }

            else -> {}
        }
    }

    private fun backToMainActivity() {
        if (SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, false)) {
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

    // ============================================================
    // MonitorCallback 实现
    // ============================================================
    override fun onClockInSuccess() {
        // 通知 TaskScheduler：打卡成功，取消超时等待分支
        TaskScheduler.notifyClockIn()
        backToMainActivity()
    }

    override fun onStartTaskCommand() {
        if (!TaskScheduler.isRunning()) {
            TaskScheduler.startTask(this)
        }
    }

    override fun onStopTaskCommand() = doStopTask()

    override fun onShowMaskCommand() {
        if (!maskViewController.isMaskVisible()) {
            maskViewController.showMaskView()
        }
    }

    override fun onHideMaskCommand() {
        if (maskViewController.isMaskVisible()) {
            maskViewController.hideMaskView()
        }
    }

    override fun onAppOpenedForScreenshot() {
        // 遥控"截屏"指令：等待 3 秒让目标 App 界面稳定，然后截屏
        lifecycleScope.launch {
            // 倒计时 3 秒，更新悬浮窗
            val target = SystemClock.elapsedRealtime() + 3000L
            while (true) {
                val remaining = target - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                val tick = (remaining / 1000).toInt()
                FloatingWindowController.updateTime(tick)
                delay(minOf(1000L, remaining).coerceAtLeast(1))
            }
            // 触发截屏
            EventBus.getDefault().post(ApplicationEvent.CaptureScreen)
            // 回到主界面并发送通知
            backToMainActivity()
            if (imagePath.isEmpty()) {
                messageDispatcher.sendMessage("截屏状态通知", "截图完成，但是无法获取截图")
            } else {
                messageDispatcher.sendAttachmentMessage("截屏状态通知", "截图完成", imagePath)
            }
        }
    }

    private fun doStopTask() {
        if (!TaskScheduler.isRunning()) return
        TaskScheduler.stopTask()
        isTaskStarted = false
        dailyTaskAdapter.updateCurrentTaskState(-1)
        binding.tipsView.text = ""
        resetExecuteButton()
        messageDispatcher.sendMessage("停止任务通知", "任务停止成功，请及时打开下次任务")
    }

    private fun resetExecuteButton() {
        binding.executeTaskButton.setIconResource(R.mipmap.ic_start)
        binding.executeTaskButton.setIconTintResource(R.color.ios_green)
        binding.executeTaskButton.text = "启动"
    }

    /**
     * 列表项单击
     * */
    private fun itemClick(position: Int) {
        if (isTaskStarted) {
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
        if (isTaskStarted) {
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

                    // 为了确保数据一致性，重新从数据库加载数据
                    taskBeans = DatabaseWrapper.loadAllTask()
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
            // 用运行模式标志判断，而非调度器内部状态（任务当天完成后调度器已闲置但仍在运行模式）
            if (isTaskStarted) {
                doStopTask()
            } else {
                if (DatabaseWrapper.loadAllTask().isEmpty()) {
                    "循环任务启动失败，请先添加任务时间点".show(this)
                    return@setOnClickListener
                }
                TaskScheduler.startTask(this)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (maskViewController.isMaskVisible()) {
                maskViewController.hideMaskView()
            } else {
                maskViewController.showMaskView()
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
                                taskBeans = DatabaseWrapper.loadAllTask()
                                dailyTaskAdapter.refresh(taskBeans)
                                binding.recyclerView.visibility = View.VISIBLE
                                binding.emptyView.visibility = View.GONE
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

        if (ProjectionSession.isStateActive()) {
            LogFileManager.writeLog("截屏服务正常：MediaProjection 有效")
        } else {
            LogFileManager.writeLog("截屏服务异常：MediaProjection 已失效")
            if (SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX) == 1) {
                "截屏服务已断开，请重新授权".show(this)
                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
            }
        }

        if (!maskViewController.isMaskVisible()) {
            maskViewController.showMaskView()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        NotificationMonitorService.monitorCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        maskViewController.destroy()
        EventBus.getDefault().unregister(this)
    }
}
