package com.pengxh.daily.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.FloatingWindowController
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
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.divider.RecyclerViewItemBorder
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.toJson
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : KotlinBaseActivity<ActivityMainBinding>() {

    private val kTag = "MainActivity"
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
    private val maskViewController by lazy { MaskViewController(this, binding, insetsController) }
    private val gestureController by lazy { GestureController(this, maskViewController) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

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

    /**
     * 每秒刷新 toolbar 时间和日期标签
     * */
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            val currentTime = dateTimeFormat.format(Date())
            val parts = currentTime.split(" ")
            binding.toolbar.apply {
                title = "${parts[2]}（${TaskScheduler.getDayFlag()}）"
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
                    if (TaskScheduler.isRunning()) {
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
                        .setCancelable(false)
                        .setPositiveButton("知道了") { _, _ -> navigatePageTo<SettingsActivity>() }
                        .show()
                }
            }
            true
        }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        binding.contentView.background = WatermarkDrawable(this, DailyTask.getWatermarkText())

        // 加载任务列表
        lifecycleScope.launch {
            taskBeans = withContext(Dispatchers.IO) {
                DatabaseWrapper.loadAllTask()
            }

            Log.d(kTag, "initOnCreate: ${taskBeans.toJson()}")

            if (taskBeans.isEmpty()) {
                binding.recyclerView.visibility = View.GONE
                binding.emptyView.visibility = View.VISIBLE
            } else {
                binding.recyclerView.visibility = View.VISIBLE
                binding.emptyView.visibility = View.GONE
            }

            binding.recyclerView.adapter = dailyTaskAdapter
            dailyTaskAdapter.refresh(taskBeans)
            binding.recyclerView.addItemDecoration(
                RecyclerViewItemBorder(
                    marginOffset, marginOffset shr 1, marginOffset, marginOffset shr 1
                )
            )
        }

        // 显示悬浮窗
        if (Settings.canDrawOverlays(this)) {
            Intent(this, FloatingWindowService::class.java).apply { startService(this) }
        } else {
            // 悬浮窗权限并显示悬浮窗
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            overlayPermissionLauncher.launch(intent)
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
                        binding.tipsView.text = "今日为周末，跳过任务"
                        binding.tipsView.setTextColor(R.color.ios_green.convertColor(this@MainActivity))
                        MessageDispatcher.sendMessage(
                            "任务跳过通知", "当前为节假日，任务已自动跳过，请注意下次打卡时间"
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
                        binding.tipsView.text = "今日任务已全部执行完毕，等待下次任务"
                        binding.tipsView.setTextColor(R.color.ios_green.convertColor(this@MainActivity))
                        LogFileManager.writeLog("今日任务已全部执行完毕")
                        MessageDispatcher.sendMessage("任务状态通知", "今日任务已全部执行完毕")
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
                        "循环任务启动失败，请先添加任务时间点".show(context)
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LogFileManager.writeLog("onNewIntent: $packageName 回到前台")

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
        mainHandler.removeCallbacksAndMessages(null)
        maskViewController.destroy()
    }

    // ================================================================
    // NotificationMonitorService 状态观察 → UI 更新
    // ================================================================

    /**
     * 根据 MonitorEvent 驱动 UI 变化
     */
    private fun handleMonitorEvent(event: MonitorEvent) {
        when (event) {
            is MonitorEvent.ClockInSuccess -> {
                TaskScheduler.notifyClockIn() // 通知 TaskScheduler：打卡成功，取消超时等待分支
                backToMainActivity()
            }

            is MonitorEvent.StartTaskCommand -> {
                if (!TaskScheduler.isRunning()) {
                    TaskScheduler.startTask()
                }
            }

            is MonitorEvent.StopTaskCommand -> doStopTask()

            is MonitorEvent.ShowMaskCommand -> {
                if (!maskViewController.isMaskVisible()) {
                    maskViewController.showMaskView()
                }
            }

            is MonitorEvent.HideMaskCommand -> {
                if (maskViewController.isMaskVisible()) {
                    maskViewController.hideMaskView()
                }
            }

            is MonitorEvent.AppOpenedForScreenshot -> {
                /**
                 * 遥控"截屏"指令完整流程：
                 *   1. 由 NotificationMonitorService 触发 openApplication
                 *   2. 等待 10 秒让目标 App 界面稳定（需要把目标APP的启动动画耗时加上）
                 *   3. 触发截屏
                 *   4. 等待截屏结果（在跳转之前，避免 lifecycle 问题）
                 *   5. 跳回 MainActivity
                 *   6. 发送通知
                 */
                lifecycleScope.launch {
                    // 倒计时 10 秒，更新悬浮窗
                    val countdownTarget = SystemClock.elapsedRealtime() + 10_000L
                    while (true) {
                        val remaining = countdownTarget - SystemClock.elapsedRealtime()
                        if (remaining <= 0) break
                        FloatingWindowController.updateTime((remaining / 1000).toInt())
                        delay(minOf(1000L, remaining).coerceAtLeast(1))
                    }

                    // 触发截屏并等待截屏结果
                    val imagePath = CaptureImageService.requestCaptureScreen().await()

                    // 回到主界面
                    backToMainActivity()

                    // 发送通知（跳回后执行，Activity 已在前台）
                    if (imagePath.isNullOrEmpty()) {
                        MessageDispatcher.sendMessage("截屏状态通知", "截图完成，但是无法获取截图")
                    } else {
                        MessageDispatcher.sendAttachmentMessage(
                            "截屏状态通知", "截图完成", imagePath
                        )
                    }
                }
            }
        }
    }

    // ================================================================
    // 用户交互
    // ================================================================

    /**
     * 列表项单击
     * */
    private fun itemClick(position: Int) {
        if (TaskScheduler.isRunning()) {
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

            lifecycleScope.launch {
                item.time = time
                withContext(Dispatchers.IO) {
                    DatabaseWrapper.updateTask(item)
                }
                taskBeans = withContext(Dispatchers.IO) {
                    DatabaseWrapper.loadAllTask()
                }
                dailyTaskAdapter.refresh(taskBeans)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * 列表项长按
     * */
    private fun itemLongClick(position: Int) {
        if (TaskScheduler.isRunning()) {
            "任务进行中，无法删除".show(this)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("删除任务")
            .setMessage("确定要删除这个任务吗？")
            .setCancelable(false) // 禁止点击外部关闭
            .setPositiveButton("确定") { _, _ ->
                try {
                    lifecycleScope.launch {
                        val item = taskBeans[position]
                        withContext(Dispatchers.IO) {
                            DatabaseWrapper.deleteTask(item)
                        }

                        // 为了确保数据一致性，重新从数据库加载数据
                        taskBeans = withContext(Dispatchers.IO) {
                            DatabaseWrapper.loadAllTask()
                        }
                        dailyTaskAdapter.refresh(taskBeans)

                        if (taskBeans.isEmpty()) {
                            binding.recyclerView.visibility = View.GONE
                            binding.emptyView.visibility = View.VISIBLE
                        } else {
                            binding.recyclerView.visibility = View.VISIBLE
                            binding.emptyView.visibility = View.GONE
                        }
                    }
                } catch (e: IndexOutOfBoundsException) {
                    e.printStackTrace()
                }
            }.setNegativeButton("取消", null).show()
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

            lifecycleScope.launch {
                val exist = withContext(Dispatchers.IO) {
                    DatabaseWrapper.isTaskTimeExist(time)
                }
                if (exist) {
                    "任务时间点已存在".show(context)
                    return@launch
                }
                binding.recyclerView.visibility = View.VISIBLE
                binding.emptyView.visibility = View.GONE
                val bean = DailyTaskBean().apply {
                    this.time = time
                }
                withContext(Dispatchers.IO) {
                    DatabaseWrapper.insert(bean)
                }
                taskBeans = withContext(Dispatchers.IO) {
                    DatabaseWrapper.loadAllTask()
                }
                dailyTaskAdapter.refresh(taskBeans)
                dialog.dismiss()
            }
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
                    // 同一个业务，可以使用同一个协程作用域，避免重复创建
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            taskDataManager.importTasks(value)
                        }
                        when (result) {
                            is TaskDataManager.ImportResult.Success -> {
                                if (result.count > 0) {
                                    taskBeans = withContext(Dispatchers.IO) {
                                        DatabaseWrapper.loadAllTask()
                                    }
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
                }

                override fun onCancelClick() {}
            }).build().show()
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

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            gestureController.onTouchEvent(it)
        }
        return super.dispatchTouchEvent(ev)
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    private fun doStopTask() {
        if (!TaskScheduler.isRunning()) return
        TaskScheduler.stopTask()
        MessageDispatcher.sendMessage("停止任务通知", "任务停止成功，请及时打开下次任务")
    }

    private fun backToMainActivity() {
        if (SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, false)) {
            //模拟点击Home键
            startActivity(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) })
            lifecycleScope.launch(Dispatchers.IO) {
                delay(1000)
                withContext(Dispatchers.Main) {
                    navigatePageTo<MainActivity>()
                }
            }
        } else {
            navigatePageTo<MainActivity>()
        }
    }

    /**
     * 兜底检查：覆盖 Alarm 未触发的场景
     * */
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
            TaskScheduler.startTask()
        }
    }

    /**
     * 悬浮窗权限启动器
     * */
    private val overlayPermissionLauncher = registerForActivityResult(permissionContract) {
        if (Settings.canDrawOverlays(this)) {
            Intent(this, FloatingWindowService::class.java).apply {
                startService(this)
            }
        }
    }
}
