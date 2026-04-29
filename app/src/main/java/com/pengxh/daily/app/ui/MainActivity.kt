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
import com.pengxh.daily.app.utils.DailyTask
import com.pengxh.daily.app.utils.DailyTaskController
import com.pengxh.daily.app.utils.GestureController
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MaskViewController
import com.pengxh.daily.app.utils.TaskDataManager
import com.pengxh.daily.app.utils.WatermarkDrawable
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.divider.RecyclerViewItemOffsets
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : KotlinBaseActivity<ActivityMainBinding>() {

    companion object {
        var isTaskStarted = false
        var isCanDrawOverlay = false;
    }

    private val context = this
    private val dateFormat by lazy {
        SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss EEEE", Locale.CHINA)
    }
    private val marginOffset by lazy { 16.dp2px(this) }
    private val permissionContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val taskDataManager by lazy { TaskDataManager() }
    private val insetsController by lazy {
        WindowCompat.getInsetsController(window, binding.rootView)
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val maskViewController by lazy { MaskViewController(this, binding, insetsController) }
    private val gestureController by lazy {
        GestureController(this, maskViewController, mainHandler)
    }
    private var taskBeans = mutableListOf<DailyTaskBean>()
    private val dailyTaskAdapter by lazy {
        DailyTaskAdapter(this, taskBeans).apply {
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
                    if (DailyTaskController.isTaskStarted()) {
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
            isCanDrawOverlay = true
        } else {
            // 悬浮窗权限并显示悬浮窗
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            overlayPermissionLauncher.launch(intent)
        }

        Intent(this, ForegroundRunningService::class.java).apply {
            startForegroundService(this)
        }

        CountDownTimerService.startService(this)

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
            RecyclerViewItemOffsets(
                marginOffset, marginOffset shr 1, marginOffset, marginOffset shr 1
            )
        )

        if (DailyTaskController.isTaskStarted()) {
            onTaskStarted()
        }
        applyExpectedMaskState()
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
                CountDownTimerService.startDailyTask(this)
            }

            is ApplicationEvent.UpdateResetTickTime -> {
                binding.repeatTimeView.text = event.countDownTime
            }

            is ApplicationEvent.StartDailyTask -> {
                if (DailyTaskController.isTaskStarted()) {
                    return
                }
                CountDownTimerService.startDailyTask(this)
            }

            is ApplicationEvent.StopDailyTask -> {
                if (!DailyTaskController.isTaskStarted()) {
                    return
                }
                CountDownTimerService.stopDailyTask(this)
            }

            is ApplicationEvent.DailyTaskStarted -> onTaskStarted()
            is ApplicationEvent.DailyTaskStopped -> onTaskStopped()
            is ApplicationEvent.DailyTaskCompleted -> onTaskCompleted()
            is ApplicationEvent.DailyTaskSkipped -> onTaskSkipped(event.message)
            is ApplicationEvent.DailyTaskExecuting -> onTaskExecuting(
                event.taskIndex,
                event.task,
                event.realTime
            )

            is ApplicationEvent.DailyTaskExecutionError -> onTaskExecutionError(event.message)

            else -> {}
        }
    }

    private fun onTaskStarted() {
        isTaskStarted = true
        binding.executeTaskButton.setIconResource(R.mipmap.ic_stop)
        binding.executeTaskButton.setIconTintResource(R.color.red)
        binding.executeTaskButton.text = "停止"
    }

    private fun onTaskStopped() {
        isTaskStarted = false
        // 重置UI状态
        dailyTaskAdapter.updateCurrentTaskState(-1)
        binding.tipsView.text = ""

        resetExecuteButton()
    }

    private fun onTaskCompleted() {
        // 任务全部完成
        isTaskStarted = false
        binding.tipsView.text = "当天所有任务已执行完毕"
        binding.tipsView.setTextColor(R.color.ios_green.convertColor(context))
        dailyTaskAdapter.updateCurrentTaskState(-1)
        resetExecuteButton()
    }

    private fun onTaskSkipped(message: String) {
        isTaskStarted = false
        binding.tipsView.text = message
        binding.tipsView.setTextColor(R.color.ios_green.convertColor(context))
        dailyTaskAdapter.updateCurrentTaskState(-1)
        resetExecuteButton()
    }

    private fun onTaskExecuting(taskIndex: Int, task: DailyTaskBean, realTime: String) {
        // 任务执行中
        binding.tipsView.text = String.format(
            Locale.getDefault(), "准备执行第 %d 个任务", taskIndex
        )
        binding.tipsView.setTextColor(R.color.theme_color.convertColor(context))
        dailyTaskAdapter.updateCurrentTaskState(taskIndex - 1, realTime)
    }

    private fun onTaskExecutionError(message: String) {
        isTaskStarted = false
        resetExecuteButton()
        binding.tipsView.text = message
        binding.tipsView.setTextColor(R.color.red.convertColor(context))
    }

    private fun resetExecuteButton() {
        binding.executeTaskButton.setIconResource(R.mipmap.ic_start)
        binding.executeTaskButton.setIconTintResource(R.color.ios_green)
        binding.executeTaskButton.text = "启动"
    }

    private val overlayPermissionLauncher = registerForActivityResult(permissionContract) {
        if (Settings.canDrawOverlays(this)) {
            Intent(this, FloatingWindowService::class.java).apply {
                startService(this)
            }
            isCanDrawOverlay = true
        } else {
            isCanDrawOverlay = false
        }
    }

    /**
     * 列表项单击
     * */
    private fun itemClick(position: Int) {
        if (DailyTaskController.isTaskStarted()) {
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
        if (DailyTaskController.isTaskStarted()) {
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
            if (DailyTaskController.isTaskStarted()) {
                CountDownTimerService.stopDailyTask(this)
            } else {
                if (DatabaseWrapper.loadAllTask().isEmpty()) {
                    "循环任务启动失败，请先添加任务时间点".show(this)
                    return@setOnClickListener
                }
                CountDownTimerService.startDailyTask(this)
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
        applyExpectedMaskState()
    }

    private fun applyExpectedMaskState() {
        if (DailyTaskController.isMaskExpectedVisible() && !maskViewController.isMaskVisible()) {
            maskViewController.showMaskView(mainHandler)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        maskViewController.destroy(mainHandler)

        mainHandler.removeCallbacksAndMessages(null)

        EventBus.getDefault().unregister(this)
    }
}
