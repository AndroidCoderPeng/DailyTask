package com.pengxh.daily.app.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.ScaleAnimation
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.github.gzuliyujiang.wheelpicker.widget.TimeWheelLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.pengxh.daily.app.R
import com.pengxh.daily.app.adapter.DailyTaskAdapter
import com.pengxh.daily.app.databinding.ActivityMainBinding
import com.pengxh.daily.app.event.FloatViewTimerEvent
import com.pengxh.daily.app.extensions.backToMainActivity
import com.pengxh.daily.app.extensions.convertToTimeEntity
import com.pengxh.daily.app.extensions.diffCurrent
import com.pengxh.daily.app.extensions.getTaskIndex
import com.pengxh.daily.app.service.CountDownTimerService
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.BroadcastManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageType
import com.pengxh.kt.lite.adapter.NormalRecyclerAdapter
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.divider.RecyclerViewItemOffsets
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.extensions.setScreenBrightness
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertControlDialog
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import com.pengxh.kt.lite.widget.dialog.AlertMessageDialog
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
import java.util.Random
import kotlin.math.abs

class MainActivity : KotlinBaseActivity<ActivityMainBinding>() {

    private val kTag = "MainActivity"
    private val context = this
    private val actions by lazy {
        listOf(
            MessageType.SHOW_MASK_VIEW.action,
            MessageType.HIDE_MASK_VIEW.action,
            MessageType.RESET_DAILY_TASK.action,
            MessageType.UPDATE_RESET_TICK_TIME.action,
            MessageType.START_DAILY_TASK.action,
            MessageType.STOP_DAILY_TASK.action,
            MessageType.CANCEL_COUNT_DOWN_TIMER.action
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss EEEE", Locale.getDefault())
    private lateinit var insetsController: WindowInsetsControllerCompat
    private var countDownTimerService: CountDownTimerService? = null
    private lateinit var gestureDetector: GestureDetector
    private lateinit var dailyTaskAdapter: DailyTaskAdapter
    private var taskBeans = mutableListOf<DailyTaskBean>()
    private val marginOffset by lazy { 16.dp2px(this) }
    private var isTaskStarted = false
    private var isRefresh = false
    private val emailManager by lazy { EmailManager(this) }
    private var timeoutTimer: CountDownTimer? = null
    private val gson by lazy { Gson() }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let {
                when (MessageType.fromAction(it)) {
                    MessageType.SHOW_MASK_VIEW -> {
                        if (!binding.maskView.isVisible) {
                            showMaskView()
                        }
                    }

                    MessageType.HIDE_MASK_VIEW -> {
                        if (binding.maskView.isVisible) {
                            hideMaskView()
                        }
                    }

                    MessageType.RESET_DAILY_TASK -> {
                        Log.d(kTag, "onReceive: 重置每日任务")
                        startExecuteTask()
                    }

                    MessageType.UPDATE_RESET_TICK_TIME -> {
                        binding.repeatTimeView.text = intent.getStringExtra("message")
                    }

                    MessageType.START_DAILY_TASK -> {
                        if (!isTaskStarted) {
                            startExecuteTask()
                        } else {
                            emailManager.sendEmail(
                                "启动任务通知",
                                "任务启动失败，任务已在运行中，请勿重复启动",
                                false
                            )
                        }
                    }

                    MessageType.STOP_DAILY_TASK -> {
                        if (isTaskStarted) {
                            stopExecuteTask()
                        } else {
                            emailManager.sendEmail(
                                "停止任务通知",
                                "任务停止失败，任务已经停止，请勿重复停止",
                                false
                            )
                        }
                    }

                    MessageType.CANCEL_COUNT_DOWN_TIMER -> {
                        timeoutTimer?.cancel()
                        timeoutTimer = null

                        LogFileManager.writeLog("取消超时定时器，执行下一个任务")
                        mainHandler.post(dailyTaskRunnable)
                    }

                    else -> {}
                }
            }
        }
    }

    override fun initViewBinding(): ActivityMainBinding {
        return ActivityMainBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        insetsController = WindowCompat.getInsetsController(window, binding.rootView)

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

                R.id.menu_settings -> navigatePageTo<SettingsActivity>()
            }
            true
        }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        BroadcastManager.getDefault().registerReceivers(this, actions, broadcastReceiver)

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

        Intent(this, ForegroundRunningService::class.java).apply {
            startForegroundService(this)
        }

        Intent(this, CountDownTimerService::class.java).apply {
            bindService(this, connection, BIND_AUTO_CREATE)
        }

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (SaveKeyValues.getValue(Constant.GESTURE_DETECTOR_KEY, false) as Boolean) {
                    val deltaY = abs(e2.y - (e1?.y ?: e2.y))

                    // 从上向下滑动手势
                    if (deltaY > 1000
                        && (e2.y - (e1?.y ?: e2.y)) > 0
                        && !binding.maskView.isVisible
                    ) {
                        showMaskView()
                        return true
                    }

                    // 从下向上滑动手势
                    if (deltaY > 1000
                        && (e2.y - (e1?.y ?: e2.y)) < 0
                        && binding.maskView.isVisible
                    ) {
                        hideMaskView()
                        return true
                    }
                }
                return super.onFling(e1, e2, velocityX, velocityY)
            }
        })

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
                marginOffset,
                marginOffset shr 1,
                marginOffset,
                marginOffset shr 1
            )
        )

        if (SaveKeyValues.getValue("isFirst", true) as Boolean) {
            AlertMessageDialog.Builder()
                .setContext(this)
                .setTitle("温馨提醒")
                .setMessage("本软件仅供内部使用，严禁商用或者用作其他非法用途")
                .setPositiveButton("知道了")
                .setOnDialogButtonClickListener(object :
                    AlertMessageDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick() {
                        SaveKeyValues.putValue("isFirst", false)
                    }
                }).build().show()
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun startFloatViewTimer(event: FloatViewTimerEvent) {
        val time = SaveKeyValues.getValue(
            Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
        ) as Int
        timeoutTimer = object : CountDownTimer(time * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val tick = millisUntilFinished / 1000
                // 更新悬浮窗倒计时
                BroadcastManager.getDefault().sendBroadcast(
                    context,
                    MessageType.UPDATE_FLOATING_WINDOW_TIME.action,
                    mapOf("tick" to tick)
                )
            }

            override fun onFinish() {
                //如果倒计时结束，那么表明没有收到打卡成功的通知
                backToMainActivity()

                LogFileManager.writeLog("未收到打卡成功通知，发送异常日志邮件")
                emailManager.sendEmail(null, "", false)
            }
        }
        timeoutTimer?.start()
    }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                Intent(this, FloatingWindowService::class.java).apply {
                    startService(this)
                }
            }
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

    /**
     * 列表项单击
     * */
    private fun itemClick(adapterPosition: Int) {
        if (isTaskStarted) {
            "任务进行中，无法修改".show(this)
            return
        }
        val item = taskBeans[adapterPosition]
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
    private fun itemLongClick(adapterPosition: Int) {
        if (isTaskStarted) {
            "任务进行中，无法删除".show(this)
            return
        }
        AlertControlDialog.Builder()
            .setContext(this)
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
                        "删除失败，请刷新重试".show(context)
                    }
                }

                override fun onCancelClick() {

                }
            }).build().show()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            gestureDetector.onTouchEvent(it)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun initEvent() {
        binding.executeTaskButton.setOnClickListener {
            if (isTaskStarted) {
                stopExecuteTask()
            } else {
                if (DatabaseWrapper.loadAllTask().isEmpty()) {
                    "循环任务启动失败，请先添加任务时间点".show(this)
                    return@setOnClickListener
                }
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
        LogFileManager.writeLog("开始执行每日任务")
        // 启动任务调度
        mainHandler.post(dailyTaskRunnable)

        // 更新状态标志
        isTaskStarted = true

        // 更新按钮状态
        binding.executeTaskButton.setIconResource(R.mipmap.ic_stop)
        binding.executeTaskButton.setIconTintResource(R.color.red)
        binding.executeTaskButton.text = "停止"

        // 发送邮件通知
        emailManager.sendEmail("启动任务通知", "任务启动成功，请注意下次打卡时间", false)
    }

    /**
     * 当日串行任务Runnable
     * */
    private val dailyTaskRunnable = object : Runnable {
        override fun run() {
            try {
                val index = taskBeans.getTaskIndex()
                if (index == -1) {
                    LogFileManager.writeLog("今日任务已全部执行完毕")
                    mainHandler.removeCallbacks(this)

                    binding.tipsView.text = "当天所有任务已执行完毕"
                    binding.tipsView.setTextColor(R.color.ios_green.convertColor(context))

                    dailyTaskAdapter.updateCurrentTaskState(-1)
                    countDownTimerService?.updateDailyTaskState()

                    emailManager.sendEmail("任务状态通知", "今日任务已全部执行完毕", false)
                    return
                }

                // 二次验证索引是否在有效范围内
                if (index < 0 || index >= taskBeans.size) {
                    LogFileManager.writeLog("任务索引超出范围: $index, 数组大小: ${taskBeans.size}")
                    return
                }

                LogFileManager.writeLog("执行任务，任务index是: $index，时间是: ${taskBeans[index].time}")
                val task = taskBeans[index]
                val taskIndex = index + 1
                binding.tipsView.text = String.format(
                    Locale.getDefault(), "准备执行第 %d 个任务", taskIndex
                )
                binding.tipsView.setTextColor(R.color.theme_color.convertColor(context))

                val pair = task.diffCurrent()
                dailyTaskAdapter.updateCurrentTaskState(index, pair.first)
                val diff = pair.second
                emailManager.sendEmail(
                    "任务执行通知",
                    "准备执行第 $taskIndex 个任务，计划时间：${task.time}，实际时间: ${pair.first}",
                    false
                )
                countDownTimerService?.startCountDown(taskIndex, diff)
            } catch (e: IndexOutOfBoundsException) {
                LogFileManager.writeLog("任务数组访问越界: ${e.message}")
            } catch (e: Exception) {
                LogFileManager.writeLog("执行任务时发生异常: ${e.message}")
            }
        }
    }

    private fun stopExecuteTask() {
        LogFileManager.writeLog("停止执行每日任务")

        // 取消任务调度
        mainHandler.removeCallbacks(dailyTaskRunnable)

        // 取消定时器
        timeoutTimer?.cancel()
        timeoutTimer = null

        // 取消服务中的倒计时
        countDownTimerService?.cancelCountDown()

        // 重置UI状态
        dailyTaskAdapter.updateCurrentTaskState(-1)
        binding.tipsView.text = ""
        isTaskStarted = false

        // 重置按钮状态
        binding.executeTaskButton.setIconResource(R.mipmap.ic_start)
        binding.executeTaskButton.setIconTintResource(R.color.ios_green)
        binding.executeTaskButton.text = "启动"

        // 发送通知
        emailManager.sendEmail("停止任务通知", "任务停止成功，请及时打开下次任务", false)
    }

    private val itemComparator = object : NormalRecyclerAdapter.ItemComparator<DailyTaskBean> {
        override fun areItemsTheSame(oldItem: DailyTaskBean, newItem: DailyTaskBean): Boolean {
            return oldItem.id == newItem.id && oldItem.time == newItem.time
        }

        override fun areContentsTheSame(oldItem: DailyTaskBean, newItem: DailyTaskBean): Boolean {
            return oldItem.time == newItem.time
        }
    }

    override fun observeRequestState() {

    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (binding.maskView.isVisible) {
                hideMaskView()
            } else {
                showMaskView()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private var clockAnimationRunnable = object : Runnable {
        override fun run() {
            // 确保视图已经布局完成
            if (binding.maskView.width == 0 || binding.maskView.height == 0) return

            // 获取时钟控件尺寸
            binding.clockView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val clockWidth = binding.clockView.measuredWidth
            val clockHeight = binding.clockView.measuredHeight

            // 计算可移动范围
            val maxX = binding.maskView.width - clockWidth
            val maxY = binding.maskView.height - clockHeight

            // 确保范围有效
            if (maxX <= 0 || maxY <= 0) return

            // 生成随机位置
            val random = Random()
            val newX = random.nextInt(maxX.coerceAtLeast(1))
            val newY = random.nextInt(maxY.coerceAtLeast(1))

            // 应用动画移动到新位置
            binding.clockView.animate()
                .x(newX.toFloat())
                .y(newY.toFloat())
                .setDuration(1000)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()

            // 每30秒执行一次位置变换
            mainHandler.postDelayed(this, 30000)
        }
    }

    /**
     * 显示蒙层以及其它组件
     * */
    private fun showMaskView() {
        //隐藏悬浮窗显示
        BroadcastManager.getDefault().sendBroadcast(this, MessageType.HIDE_FLOATING_WINDOW.action)

        //隐藏状态栏和导航栏显示
        insetsController.apply {
            hide(WindowInsetsCompat.Type.statusBars())
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        //显示蒙层
        binding.maskView.visibility = View.VISIBLE
        val visibleAction = ScaleAnimation(1.0f, 1.0f, 0.0f, 1.0f)
        visibleAction.duration = 500
        binding.maskView.startAnimation(visibleAction)
        window.setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF)

        //隐藏任务界面
        binding.rootView.visibility = View.GONE

        //启动时钟位置变换动画
        mainHandler.postDelayed(clockAnimationRunnable, 30000)
    }

    /**
     * 隐藏蒙层以及其它组件
     * */
    private fun hideMaskView() {
        //恢复悬浮窗显示
        BroadcastManager.getDefault().sendBroadcast(this, MessageType.SHOW_FLOATING_WINDOW.action)

        //停止时钟动画
        mainHandler.removeCallbacks(clockAnimationRunnable)

        //恢复状态栏和导航栏显示
        insetsController.apply {
            show(WindowInsetsCompat.Type.statusBars())
            show(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }

        //隐藏蒙层
        binding.maskView.visibility = View.GONE
        val invisibleAction = ScaleAnimation(1.0f, 1.0f, 1.0f, 0.0f)
        invisibleAction.duration = 500
        binding.maskView.startAnimation(invisibleAction)
        window.setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)

        //显示任务界面
        binding.rootView.visibility = View.VISIBLE
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
                        "任务导入成功".show(context)
                    } catch (e: JsonSyntaxException) {
                        e.printStackTrace()
                        "导入失败，请确认导入的是正确的任务数据".show(context)
                    }
                }

                override fun onCancelClick() {}
            }).build().show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(kTag, "onNewIntent: ${packageName}回到前台")
        if (!binding.maskView.isVisible) {
            showMaskView()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        actions.forEach {
            BroadcastManager.getDefault().unregisterReceiver(this, it)
        }
        EventBus.getDefault().unregister(this)
    }
}