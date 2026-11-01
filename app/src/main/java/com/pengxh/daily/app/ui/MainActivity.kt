package com.pengxh.daily.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pengxh.daily.app.R
import com.pengxh.daily.app.adapter.DailyTaskAdapter
import com.pengxh.daily.app.databinding.ActivityMainBinding
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.ui.main.TaskTimeDialog
import com.pengxh.daily.app.utils.AppConfig
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.GestureController
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MaskViewController
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.MonitorEvent
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.utils.TaskDataManager
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.daily.app.utils.TipsEvent
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.daily.app.utils.todayDateKey
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.divider.RecyclerViewItemBorder
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class MainActivity : KotlinBaseActivity<ActivityMainBinding>() {

    private val marginOffset by lazy { 16.dp2px(this) }
    private val permissionContract = ActivityResultContracts.StartActivityForResult()
    private val taskDataManager by lazy { TaskDataManager() }

    private val insetsController by lazy {
        WindowCompat.getInsetsController(window, binding.rootView)
    }
    private val maskViewController by lazy { MaskViewController(this, binding, insetsController) }
    private val gestureController by lazy {
        GestureController(this, maskViewController, ::saveMaskMode)
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 当前任务列表快照
     *
     * 只读引用，adapter 内部持有自己的副本，两者不共享同一个 List 对象，
     * 避免"页面数据源"与"adapter 数据源"分叉后错位。
     * */
    private var taskBeans: List<DailyTaskBean> = emptyList()

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

    /**
     * 悬浮窗权限启动器
     * */
    private val overlayPermissionLauncher = registerForActivityResult(permissionContract) {
        startFloatingWindowService()
    }

    /**
     * 每秒刷新 toolbar 时间和日期标签
     * */
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            val now = Calendar.getInstance()
            val month = now.get(Calendar.MONTH) + 1
            val day = now.get(Calendar.DAY_OF_MONTH)
            binding.toolbar.apply {
                title = String.format(
                    Locale.CHINA, "%02d月%02d日（%s）", month, day, TaskScheduler.getDayFlag()
                )
                subtitle = String.format(
                    Locale.CHINA, "%04d-%02d-%02d %02d:%02d:%02d",
                    now.get(Calendar.YEAR),
                    month,
                    day,
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    now.get(Calendar.SECOND)
                )
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
            onToolbarMenuClick(menuItem.itemId)
            true
        }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        binding.contentView.background = WatermarkDrawable(this, DailyTask.getWatermarkText())

        binding.recyclerView.adapter = dailyTaskAdapter
        binding.recyclerView.addItemDecoration(
            RecyclerViewItemBorder(
                marginOffset, marginOffset shr 1, marginOffset, marginOffset shr 1
            )
        )

        // 加载任务列表
        lifecycleScope.launch {
            reloadTasks()
        }

        // 显示悬浮窗
        if (Settings.canDrawOverlays(this)) {
            startFloatingWindowService()
        } else {
            // 悬浮窗权限并显示悬浮窗
            overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }

        // 前台服务（保活 + 托管 TaskScheduler 协程作用域 + 每日重置）
        Intent(this, ForegroundRunningService::class.java).apply { startForegroundService(this) }

        // ================================================================
        // 每个 lifecycleScope.launch 都是独立的协程，互斥，不能为了省事把协程合并，否则只会执行第一个协程的业务，其他的业务被挂起
        // ================================================================

        // 订阅每日重置时间倒计时
        lifecycleScope.launch {
            ForegroundRunningService.resetTickTime.collect { text ->
                binding.repeatTimeView.text = text
            }
        }

        // 订阅通知监听事件
        lifecycleScope.launch {
            NotificationMonitorService.events.collect { event -> handleMonitorEvent(event) }
        }

        // 订阅调度器运行状态 → 按钮 UI
        lifecycleScope.launch {
            TaskScheduler.isRunning.collectLatest { running ->
                if (running) {
                    binding.executeTaskButton.setIconResource(R.mipmap.ic_stop)
                    binding.executeTaskButton.setIconTintResource(R.color.red)
                    binding.executeTaskButton.text = "停止"
                } else {
                    dailyTaskAdapter.updateCurrentTaskState(-1)
                    binding.tipsView.text = ""
                    binding.executeTaskButton.setIconResource(R.mipmap.ic_start)
                    binding.executeTaskButton.setIconTintResource(R.color.ios_green)
                    binding.executeTaskButton.text = "启动"
                }
            }
        }

        // 订阅超时回主页信号
        lifecycleScope.launch {
            TaskScheduler.returnToApp.collectLatest {
                backToMainActivity()
            }
        }

        // 订阅 TipsEvent → tipsView + adapter 高亮
        lifecycleScope.launch {
            TaskScheduler.tipsEvent.collectLatest { event ->
                when (event) {
                    is TipsEvent.Skip -> {
                        val reason = "今日为${event.dayFlag.label}"
                        binding.tipsView.text = "$reason，跳过任务"
                        binding.tipsView.setTextColor(R.color.ios_green.convertColor(this@MainActivity))
                        MessageDispatcher.sendMessage(
                            "任务跳过通知",
                            "$reason，任务已自动跳过，请注意下次打卡时间"
                        )
                    }

                    is TipsEvent.Executing -> {
                        binding.tipsView.text = "准备执行第 ${event.index} 个任务"
                        binding.tipsView.setTextColor(R.color.theme_color.convertColor(this@MainActivity))
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
                        val statusColor =
                            if (event.hasFailure) R.color.red else R.color.ios_green
                        binding.tipsView.setTextColor(statusColor.convertColor(this@MainActivity))
                        LogFileManager.writeLog(message)
                        MessageDispatcher.sendMessage("任务状态通知", message)
                    }
                }
            }
        }

        // 兜底检查是否有错过的每日重置
        checkMissedReset()
    }

    override fun initEvent() {
        binding.executeTaskButton.setOnClickListener {
            if (TaskScheduler.isRunning()) {
                doStopTask()
            } else {
                lifecycleScope.launch {
                    val isEmpty = withContext(Dispatchers.IO) {
                        DatabaseWrapper.loadAllTask().isEmpty()
                    }
                    if (isEmpty) {
                        "循环任务启动失败，请先添加任务时间点".show(this@MainActivity)
                        return@launch
                    }
                    TaskScheduler.startTask()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Settings.canDrawOverlays(this)) {
            "悬浮窗权限未开启，部分功能可能无法正常使用".show(this)
        }
        showMaskOnForeground()
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
        mainHandler.removeCallbacksAndMessages(null)
        maskViewController.destroy()
    }

    // ================================================================
    // 任务列表
    // ================================================================

    /**
     * 重新从数据库加载任务列表并刷新 UI
     *
     * 所有"增删改之后刷新列表"的场景统一走这里，
     * 避免加载、刷新、空态切换三段逻辑散落在多个回调里。
     * */
    private suspend fun reloadTasks() {
        val list = withContext(Dispatchers.IO) {
            DatabaseWrapper.loadAllTask()
        }
        taskBeans = list
        dailyTaskAdapter.refresh(list)
        updateEmptyState(list.isEmpty())
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
        // 协程外先取快照，避免协程执行时列表已刷新导致下标越界
        val item = taskBeans.getOrNull(position) ?: return

        TaskTimeDialog.show(this, "修改任务时间", item.time) { time ->
            lifecycleScope.launch {
                item.time = time
                withContext(Dispatchers.IO) {
                    DatabaseWrapper.updateTask(item)
                }
                reloadTasks()
            }
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
        // 协程外先取快照，避免协程执行时列表已刷新导致下标越界
        val item = taskBeans.getOrNull(position) ?: return

        MaterialAlertDialogBuilder(this)
            .setTitle("删除任务")
            .setMessage("确定要删除这个任务吗？")
            .setCancelable(false) // 禁止点击外部关闭
            .setPositiveButton("确定") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        DatabaseWrapper.deleteTask(item)
                    }
                    // 为了确保数据一致性，重新从数据库加载数据
                    reloadTasks()
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun createTask() {
        TaskTimeDialog.show(this, "添加任务") { time ->
            lifecycleScope.launch {
                val exist = withContext(Dispatchers.IO) {
                    DatabaseWrapper.isTaskTimeExist(time)
                }
                if (exist) {
                    "任务时间点已存在".show(this@MainActivity)
                    return@launch
                }
                val bean = DailyTaskBean().apply {
                    this.time = time
                }
                withContext(Dispatchers.IO) {
                    DatabaseWrapper.insert(bean)
                }
                reloadTasks()
                checkMissedReset()
            }
        }
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
                    // 同一个业务，可以使用同一个协程作用域，避免重复创建
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            taskDataManager.importTasks(value)
                        }
                        when (result) {
                            is TaskDataManager.ImportResult.Success -> {
                                if (result.count > 0) {
                                    reloadTasks()
                                    ForegroundRunningService.emitResetTaskTime()
                                    checkMissedReset()
                                }
                                if (result.warnings.isEmpty()) {
                                    "任务导入成功".show(this@MainActivity)
                                } else {
                                    MaterialAlertDialogBuilder(this@MainActivity)
                                        .setTitle("导入完成")
                                        .setMessage(
                                            "任务导入成功\n\n${result.warnings.joinToString("\n")}"
                                        )
                                        .setPositiveButton("知道了", null)
                                        .show()
                                }
                            }

                            is TaskDataManager.ImportResult.Error -> {
                                result.message.show(this@MainActivity)
                            }
                        }
                    }
                }

                override fun onCancelClick() {}
            }).build().show()
    }

    // ================================================================
    // 工具栏菜单
    // ================================================================

    private fun onToolbarMenuClick(itemId: Int) {
        when (itemId) {
            R.id.menu_add_task -> {
                if (TaskScheduler.isRunning()) {
                    showTaskRunningToast("添加")
                    return
                }

                if (taskBeans.isNotEmpty()) {
                    createTask()
                } else {
                    showAddOrImportSheet()
                }
            }

            R.id.menu_settings -> showUsageNotice()
        }
    }

    /**
     * 任务列表为空时，让用户选择"添加"还是"导入"
     * */
    private fun showAddOrImportSheet() {
        BottomActionSheet.Builder()
            .setContext(this)
            .setActionItemTitle(arrayListOf("添加任务", "导入任务"))
            .setItemTextColor(R.color.theme_color.convertColor(this))
            .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                override fun onActionItemClick(position: Int) {
                    when (position) {
                        0 -> createTask()
                        1 -> importTask()
                    }
                }
            }).build().show()
    }

    private fun showUsageNotice() {
        MaterialAlertDialogBuilder(this)
            .setTitle("使用须知")
            .setMessage("本软件完全免费！仅供内部使用！严禁商用或者用作其他非法用途！\r\n近期发现有人在咸鱼私自倒卖本软件，请勿购买！如有购买，请联系卖家退款！")
            .setCancelable(false)
            .setPositiveButton("知道了") { _, _ -> navigatePageTo<SettingsActivity>() }
            .show()
    }

    // ================================================================
    // NotificationMonitorService 状态观察 → UI 更新
    // ================================================================
    private fun handleMonitorEvent(event: MonitorEvent) {
        when (event) {
            is MonitorEvent.ClockInSuccess -> backToMainActivity()
            is MonitorEvent.ShowMaskCommand -> setMaskVisible(true)
            is MonitorEvent.HideMaskCommand -> setMaskVisible(false)
            is MonitorEvent.ReturnToApp -> backToMainActivity()
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

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            gestureController.onTouchEvent(it)
        }
        return super.dispatchTouchEvent(ev)
    }

    // ================================================================
    // 任务调度
    // ================================================================

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
        val today = todayDateKey()

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

    // ================================================================
    // 悬浮窗
    // ================================================================

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
