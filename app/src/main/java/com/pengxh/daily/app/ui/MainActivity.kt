package com.pengxh.daily.app.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pengxh.daily.app.R
import com.pengxh.daily.app.adapter.DailyTaskAdapter
import com.pengxh.daily.app.base.KotlinBaseActivity
import com.pengxh.daily.app.databinding.ActivityMainBinding
import com.pengxh.daily.app.events.MainEvent
import com.pengxh.daily.app.events.MonitorEvent
import com.pengxh.daily.app.events.TipsEvent
import com.pengxh.daily.app.extensions.navigatePageTo
import com.pengxh.daily.app.extensions.show
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.utils.AppConfig
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.CustomWorkdayManager
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.GestureController
import com.pengxh.daily.app.utils.Key
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MainDialogManager
import com.pengxh.daily.app.utils.MaskViewController
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.utils.TaskListState
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.daily.app.vm.MainViewModel
import com.pengxh.daily.app.widget.TaskTimeDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

class MainActivity : KotlinBaseActivity<ActivityMainBinding>() {

    private val permissionContract = ActivityResultContracts.StartActivityForResult()

    private val mainViewModel: MainViewModel by viewModels()

    private val insetsController by lazy {
        WindowCompat.getInsetsController(window, binding.rootView)
    }
    private val maskViewController by lazy { MaskViewController(this, binding, insetsController) }
    private val gestureController by lazy {
        GestureController(this, maskViewController, ::saveMaskMode)
    }

    private val dailyTaskAdapter by lazy {
        DailyTaskAdapter().apply {
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

    private val overlayPermissionLauncher = registerForActivityResult(permissionContract) {
        startFloatingWindowService()
    }

    override fun initViewBinding(): ActivityMainBinding {
        return ActivityMainBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, systemBars.top, view.paddingRight, systemBars.bottom)
            insets
        }

        WindowInsetsControllerCompat(window, binding.rootView).isAppearanceLightStatusBars = true

        updateToolbarTitle()
    }

    private fun updateToolbarTitle() {
        val today = LocalDate.now()
        binding.toolbar.title = String.format(
            Locale.CHINA,
            "%02d月%02d日 %s（%s）",
            today.monthValue,
            today.dayOfMonth,
            CustomWorkdayManager.getDayLabel(today.dayOfWeek),
            TaskScheduler.getDayFlag()
        )
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        binding.contentView.background = WatermarkDrawable(this, DailyTask.getWatermarkText())
        binding.recyclerView.adapter = dailyTaskAdapter

        startCoreServices()

        updateResetTimeText()

        // 兜底检查是否有错过的每日重置
        checkMissedReset()
    }

    override fun observeRequestState() {
        observeTaskListState()
        observeMainEvents()
        observeResetCountdown()
        observeResetTaskTime()
        observeMonitorEvents()
        observeSchedulerState()
        observeReturnToApp()
        observeTipsEvents()
    }

    override fun initEvent() {
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_add_task -> {
                    if (TaskScheduler.isRunning()) {
                        showTaskRunningToast("添加")
                        return@setOnMenuItemClickListener true
                    }

                    TaskTimeDialog.show(this, "添加任务") { time ->
                        mainViewModel.addTask(time)
                    }
                }

                R.id.menu_settings -> MainDialogManager.showUsageNotice(this) {
                    navigatePageTo<SettingsActivity>()
                }
            }
            true
        }

        binding.executeTaskButton.setOnClickListener {
            if (TaskScheduler.isRunning()) {
                doStopTask()
            } else {
                lifecycleScope.launch {
                    if (!mainViewModel.hasTasks()) {
                        "循环任务启动失败，请先添加任务时间点".show(this@MainActivity)
                        return@launch
                    }
                    TaskScheduler.startTask()
                }
            }
        }

        binding.importTaskView.setOnClickListener {
            MainDialogManager.showImportInput(this) { json ->
                mainViewModel.importTasks(json)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LogFileManager.writeLog("onResume: $packageName 回到前台")
        if (!Settings.canDrawOverlays(this)) {
            "悬浮窗权限未开启，部分功能可能无法正常使用".show(this)
        }
        showMaskOnForeground()
        updateToolbarTitle()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LogFileManager.writeLog("onNewIntent: $packageName 回到前台")

        if (ProjectionSession.isStateActive()) {
            LogFileManager.writeLog("截屏服务正常：MediaProjection 有效")
        } else {
            LogFileManager.writeLog("截屏服务异常：MediaProjection 已失效")
            if (AppConfig.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX) == 1) {
                "截屏服务已断开，请重新授权".show(this)
                AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, 0)
            }
        }

        // 回到前台无条件显示蒙层（省电），与任务是否开启无关
        showMaskOnForeground()
    }

    override fun onDestroy() {
        super.onDestroy()
        maskViewController.destroy()
    }

    /**
     * 窗口重新获得焦点时兜底隐藏系统栏
     *
     * decorView.post 执行的时机可能早于窗口真正拿到焦点，此时 hide() 可能失效，
     * 因此这里再补一次，确保伪灭屏状态下状态栏一定是隐藏的
     * */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && maskViewController.isMaskVisible()) {
            maskViewController.hideSystemBars()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val maskVisible = !maskViewController.isMaskVisible()
            setMaskVisible(maskVisible)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            gestureController.onTouchEvent(it)
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 启动核心服务：悬浮窗 + 前台服务
     *
     * 前台服务负责保活、托管 TaskScheduler 协程作用域以及每日重置
     * */
    private fun startCoreServices() {
        // 显示悬浮窗
        if (Settings.canDrawOverlays(this)) {
            startFloatingWindowService()
        } else {
            // 悬浮窗权限并显示悬浮窗
            overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }

        Intent(this, ForegroundRunningService::class.java).apply { startForegroundService(this) }
    }

    /**
     * 订阅任务列表状态 → 渲染列表与空态
     * */
    private fun observeTaskListState() {
        lifecycleScope.launch {
            mainViewModel.taskListState.collect { state ->
                when (state) {
                    // 还没加载完，保持布局默认状态，避免空态闪一下
                    is TaskListState.Initial -> Unit

                    is TaskListState.Loaded -> {
                        dailyTaskAdapter.refresh(state.tasks)
                        updateEmptyState(state.tasks.isEmpty())
                    }
                }
            }
        }
    }

    private fun observeMainEvents() {
        lifecycleScope.launch {
            mainViewModel.events.collect { event ->
                when (event) {
                    MainEvent.TaskTimeExists -> "任务时间点已存在".show(this@MainActivity)

                    MainEvent.TaskAdded -> checkMissedReset()

                    is MainEvent.ImportSuccess -> handleImportSuccess(event)

                    is MainEvent.ImportError -> event.message.show(this@MainActivity)
                }
            }
        }
    }

    private fun observeResetCountdown() {
        lifecycleScope.launch {
            ForegroundRunningService.resetTickTime.collect { text ->
                binding.repeatTimeView.text = text
                updateToolbarTitle()
            }
        }
    }

    private fun observeResetTaskTime() {
        lifecycleScope.launch {
            ForegroundRunningService.resetTaskTime.collect {
                updateResetTimeText()
            }
        }
    }

    private fun updateResetTimeText() {
        val resetHour = AppConfig.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        binding.resetTimeView.text = String.format(Locale.CHINA, "每天 %02d:00 重置", resetHour)
    }

    private fun updateStatePill(running: Boolean) {
        binding.stateTextView.text = if (running) "运行中" else "未运行"
        val colorRes = if (running) R.color.success_color else R.color.text_secondary_color
        binding.stateTextView.setTextColor(getColor(colorRes))
        binding.stateCardView.setCardBackgroundColor(
            if (running) {
                // 成功色 12% 透明度软背景
                ColorUtils.setAlphaComponent(getColor(R.color.success_color), 0x1F)
            } else {
                getColor(R.color.back_ground_color)
            }
        )
    }

    private fun observeMonitorEvents() {
        lifecycleScope.launch {
            NotificationMonitorService.events.collect { event -> handleMonitorEvent(event) }
        }
    }

    private fun observeSchedulerState() {
        lifecycleScope.launch {
            TaskScheduler.isRunning.collectLatest { running ->
                updateStatePill(running)
                binding.executeTaskButton.setIconTintResource(R.color.white)
                if (running) {
                    binding.executeTaskButton.apply {
                        setIconResource(R.mipmap.ic_stop)
                        backgroundTintList = getColorStateList(R.color.red)
                        text = "停止今日任务"
                    }
                } else {
                    dailyTaskAdapter.updateCurrentTaskState(-1)
                    binding.tipsView.text = ""
                    binding.executeTaskButton.apply {
                        setIconResource(R.mipmap.ic_start)
                        backgroundTintList = getColorStateList(R.color.theme_color)
                        text = "启动今日任务"
                    }
                }
            }
        }
    }

    /**
     * 订阅超时回主页信号
     * */
    private fun observeReturnToApp() {
        lifecycleScope.launch {
            TaskScheduler.returnToApp.collectLatest {
                backToMainActivity()
            }
        }
    }

    /**
     * 订阅 TipsEvent → tipsView + adapter 高亮
     * */
    private fun observeTipsEvents() {
        lifecycleScope.launch {
            TaskScheduler.tipsEvent.collectLatest { event -> handleTipsEvent(event) }
        }
    }

    /**
     * NotificationMonitorService 状态观察 → UI 更新
     * */
    private fun handleMonitorEvent(event: MonitorEvent) {
        when (event) {
            is MonitorEvent.ClockInSuccess -> backToMainActivity()
            is MonitorEvent.ShowMaskCommand -> setMaskVisible(true)
            is MonitorEvent.HideMaskCommand -> setMaskVisible(false)
            is MonitorEvent.ReturnToApp -> backToMainActivity()
        }
    }

    /**
     * TaskScheduler 任务事件 → UI 更新
     * */
    private fun handleTipsEvent(event: TipsEvent) {
        when (event) {
            is TipsEvent.Skip -> {
                val reason = "今日为${event.dayFlag.label}"
                binding.tipsView.text = "$reason，跳过任务"
                MessageDispatcher.sendMessage(
                    "任务跳过通知",
                    "$reason，任务已自动跳过，请注意下次打卡时间"
                )
            }

            is TipsEvent.Executing -> {
                binding.tipsView.text = "准备执行第 ${event.index} 个任务"
                dailyTaskAdapter.updateCurrentTaskState(event.index - 1, event.actualTime)

                val content = buildString {
                    appendLine("准备执行第 ${event.index} 个任务")
                    appendLine("计划时间：${event.plannedTime}")
                    append("实际时间：${event.actualTime}")
                }
                MessageDispatcher.sendMessage("任务执行通知", content)
            }

            is TipsEvent.Completed -> {
                dailyTaskAdapter.updateCurrentTaskState(-1)
                val message = event.message ?: "今日任务已全部执行完毕"
                binding.tipsView.text = "$message，等待下次任务"
                LogFileManager.writeLog(message)
                MessageDispatcher.sendMessage("任务状态通知", message)
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    /**
     * 列表项单击：修改任务时间
     * */
    private fun itemClick(position: Int) {
        if (TaskScheduler.isRunning()) {
            showTaskRunningToast("修改")
            return
        }
        val item = dailyTaskAdapter.itemAt(position) ?: return
        TaskTimeDialog.show(this, "修改任务时间", item.time) { time ->
            mainViewModel.updateTaskTime(item, time)
        }
    }

    /**
     * 列表项长按：删除任务
     * */
    private fun itemLongClick(position: Int) {
        if (TaskScheduler.isRunning()) {
            showTaskRunningToast("删除")
            return
        }
        val item = dailyTaskAdapter.itemAt(position) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("删除任务")
            .setMessage("确定要删除这个任务吗？")
            .setCancelable(false) // 禁止点击外部关闭
            .setPositiveButton("确定") { _, _ -> mainViewModel.deleteTask(item) }
            .setNegativeButton("取消", null).show()
    }

    private fun handleImportSuccess(event: MainEvent.ImportSuccess) {
        if (event.count > 0) {
            ForegroundRunningService.emitResetTaskTime()
            checkMissedReset()
        }
        if (event.warnings.isEmpty()) {
            "任务导入成功".show(this@MainActivity)
        } else {
            MainDialogManager.showImportResult(this@MainActivity, event.warnings)
        }
    }

    /**
     * 回到前台一律显示蒙层（省电）：
     * 1. 不依赖 REMOTE_MASK_MODE_KEY 历史存档，避免上次手动退出蒙层后，再次回前台蒙层不显示
     * 2. 与任务是否开启无关
     * 3. 延迟到窗口就绪后执行，保证隐藏系统栏、降低亮度等操作生效
     * 4. 用户仍可通过上滑手势或者音量下键退出蒙层
     * 5. 隐藏系统栏由 setMaskVisible(true) 无条件负责，此处不再重复处理
     * */
    private fun showMaskOnForeground() {
        window.decorView.post { setMaskVisible(true) }
    }

    /**
     * 切换蒙层显示状态，并同步隐藏系统栏
     * */
    private fun setMaskVisible(visible: Boolean) {
        saveMaskMode(visible)
        if (visible) {
            maskViewController.hideSystemBars()
            if (!maskViewController.isMaskVisible()) {
                maskViewController.showMaskView()
            }
        } else if (maskViewController.isMaskVisible()) {
            maskViewController.hideMaskView()
        }
    }

    private fun saveMaskMode(visible: Boolean) {
        val value = if (visible) 1 else 0
        // 状态没变就不写盘，避免每次回前台都产生一次无意义的 SP 写入
        if (AppConfig.loadInt(Constant.REMOTE_MASK_MODE_KEY, -1) != value) {
            AppConfig.saveInt(Constant.REMOTE_MASK_MODE_KEY, value)
        }
    }

    private fun doStopTask() {
        if (!TaskScheduler.isRunning()) return
        TaskScheduler.stopTask()
        MessageDispatcher.sendMessage("停止任务通知", "任务停止成功，请及时打开下次任务")
    }

    private fun backToMainActivity() {
        if (AppConfig.loadBoolean(Constant.BACK_TO_HOME_KEY, false)) {
            //模拟点击Home键
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            })
            lifecycleScope.launch {
                delay(1000)
                this@MainActivity.navigatePageTo<MainActivity>()
            }
        } else {
            navigatePageTo<MainActivity>()
        }
    }

    /**
     * 兜底检查：覆盖 Alarm 未触发的场景
     * */
    private fun checkMissedReset() {
        val lastResetDate = AppConfig.loadString(Constant.LAST_RESET_DATE_KEY, "")
        val today = Key.todayDateKey()

        // 今天已重置，跳过（防止重复执行）
        if (lastResetDate == today) {
            return
        }

        // 今天还未重置，执行重置（覆盖 Alarm 未触发的场景）
        LogFileManager.writeLog("检测到今日尚未重置，执行重置操作")
        if (AppConfig.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)) {
            TaskScheduler.startTask()
        }
    }

    /**
     * 启动悬浮窗服务（无悬浮窗权限时直接跳过）
     * */
    private fun startFloatingWindowService() {
        if (!Settings.canDrawOverlays(this)) return
        Intent(this, FloatingWindowService::class.java).apply { startService(this) }
    }

    /**
     * 任务执行中禁止增删改时的统一提示
     * */
    private fun showTaskRunningToast(action: String) {
        "任务进行中，无法$action".show(this@MainActivity)
    }
}
