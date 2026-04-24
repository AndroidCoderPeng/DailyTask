package com.pengxh.daily.app.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.service.CountDownTimerService
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.ui.MainActivity
import com.pengxh.kt.lite.extensions.timestampToDate
import com.pengxh.kt.lite.utils.SaveKeyValues
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

object DailyTaskController : TaskScheduler.TaskStateListener {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val timeoutTimerManager by lazy { TimeoutTimerManager(mainHandler) }
    private val taskScheduler by lazy { TaskScheduler(mainHandler, this) }
    private var appContext: Context? = null
    private var eventRegistered = false
    private var imagePath = ""
    private var hasCaptured = false

    @Volatile
    private var taskStarted = false

    fun attachCountDownTimerService(service: CountDownTimerService) {
        appContext = service.applicationContext
        ensureEventRegistered()
        taskScheduler.setCountDownTimerService(service)
    }

    fun detachCountDownTimerService(service: CountDownTimerService) {
        if (appContext === service.applicationContext) {
            taskScheduler.setCountDownTimerService(null)
        }
    }

    fun isTaskStarted(): Boolean {
        val savedState = SaveKeyValues.getValue(
            Constant.TASK_RUNNING_STATE_KEY, false
        ) as Boolean
        return taskScheduler.isTaskStarted() || taskStarted || savedState
    }

    fun startTask(context: Context) {
        appContext = context.applicationContext
        ensureEventRegistered()
        if (taskScheduler.isTaskStarted()) {
            taskScheduler.executeNextTask()
        } else {
            taskScheduler.startTask()
        }
    }

    fun stopTask() {
        taskScheduler.stopTask()
    }

    fun executeNextTask() {
        taskScheduler.executeNextTask()
    }

    fun handleTaskSuccess(context: Context) {
        appContext = context.applicationContext
        timeoutTimerManager.cancelTimeoutTimer()
        backToMainActivity()
        taskScheduler.executeNextTask()
    }

    fun setMaskVisible(context: Context, visible: Boolean) {
        appContext = context.applicationContext
        startMainActivity(context.applicationContext)
        val event = if (visible) {
            ApplicationEvent.ShowMaskView
        } else {
            ApplicationEvent.HideMaskView
        }
        EventBus.getDefault().post(event)
        mainHandler.postDelayed({ EventBus.getDefault().post(event) }, 500)
    }

    fun openTargetApplication(
        context: Context,
        trackTaskResult: Boolean,
        remoteScreenshot: Boolean = false
    ) {
        appContext = context.applicationContext
        ensureEventRegistered()
        val targetApp = Constant.getTargetApp()
        if (!isApplicationExist(context, targetApp)) {
            sendChannelMessage("任务执行出错通知", "未安装指定的目标软件，无法执行任务")
            taskScheduler.stopTask()
            return
        }

        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(targetApp)
        }
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            context.packageManager.queryIntentActivities(intent, 0)
        }

        if (activities.isEmpty()) {
            sendChannelMessage("任务执行出错通知", "未找到目标应用入口，无法执行任务")
            taskScheduler.stopTask()
            return
        }

        val info = activities.first()
        intent.component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
        context.startActivity(intent)

        when {
            remoteScreenshot -> startRemoteScreenshotTimer()
            trackTaskResult -> startTaskTimeoutTimer()
        }
    }

    private fun startRemoteScreenshotTimer() {
        imagePath = ""
        hasCaptured = false
        object : android.os.CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val tick = (millisUntilFinished / 1000).toInt()
                EventBus.getDefault().post(ApplicationEvent.UpdateFloatingViewTime(tick))
                if (tick <= 2 && !hasCaptured) {
                    hasCaptured = true
                    EventBus.getDefault().post(ApplicationEvent.CaptureScreen)
                }
            }

            override fun onFinish() {
                backToMainActivity()
                if (imagePath.isBlank()) {
                    sendChannelMessage("截屏状态通知", "截图完成，但是无法获取截图，请手动查看结果")
                } else {
                    sendAttachmentMessage("截屏状态通知", "截图完成，结果请查看附件", imagePath)
                }
                hasCaptured = false
            }
        }.start()
    }

    private fun startTaskTimeoutTimer() {
        imagePath = ""
        timeoutTimerManager.startTimeoutTimer {
            backToMainActivity()

            val resultSource = SaveKeyValues.getValue(Constant.RESULT_SOURCE_KEY, 0) as Int
            if (resultSource == 0) {
                sendChannelMessage("", "")
            } else {
                if (imagePath.isBlank()) {
                    sendChannelMessage("", "打卡完成，但是无法获取截图，请手动查看结果")
                } else {
                    sendAttachmentMessage("", "打卡完成，结果请查看附件", imagePath)
                }
            }

            taskScheduler.executeNextTask()
        }
    }

    private fun backToMainActivity() {
        val context = appContext ?: return
        if (SaveKeyValues.getValue(Constant.BACK_TO_HOME_KEY, false) as Boolean) {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(home)
            mainHandler.postDelayed({ startMainActivity(context) }, 2000)
        } else {
            startMainActivity(context)
        }
    }

    private fun startMainActivity(context: Context) {
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(this)
        }
    }

    override fun onTaskStarted() {
        updateTaskStartedState(true)
        EventBus.getDefault().post(ApplicationEvent.DailyTaskStarted)
        sendChannelMessage("启动任务通知", "任务启动成功，请注意下次打卡时间")
    }

    override fun onTaskStopped() {
        updateTaskStartedState(false)
        timeoutTimerManager.cancelTimeoutTimer()
        EventBus.getDefault().post(ApplicationEvent.DailyTaskStopped)
        sendChannelMessage("停止任务通知", "任务停止成功，请及时打开下次任务")
    }

    override fun onTaskCompleted() {
        updateTaskStartedState(false)
        timeoutTimerManager.cancelTimeoutTimer()
        EventBus.getDefault().post(ApplicationEvent.DailyTaskCompleted)
        sendChannelMessage("任务状态通知", "今日任务已全部执行完毕")
    }

    override fun onTaskExecuting(taskIndex: Int, task: DailyTaskBean, realTime: String) {
        EventBus.getDefault().post(ApplicationEvent.DailyTaskExecuting(taskIndex, task, realTime))
        val content = buildString {
            appendLine("准备执行第 $taskIndex 个任务")
            appendLine("计划时间：${task.time}")
            append("实际时间：$realTime")
        }
        sendChannelMessage("任务执行通知", content)
    }

    override fun onTaskExecutionError(message: String) {
        updateTaskStartedState(false)
        EventBus.getDefault().post(ApplicationEvent.DailyTaskExecutionError(message))
        sendChannelMessage("任务执行出错通知", message)
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun handleApplicationEvent(event: ApplicationEvent) {
        if (event is ApplicationEvent.CaptureCompleted) {
            imagePath = event.imagePath
        }
    }

    private fun sendChannelMessage(title: String, content: String) {
        val context = appContext ?: return
        val messageTitle = SaveKeyValues.getValue(
            Constant.MESSAGE_TITLE_KEY, "打卡结果通知"
        ) as String
        val channelType = SaveKeyValues.getValue(Constant.CHANNEL_TYPE_KEY, -1) as Int
        when (channelType) {
            0 -> {
                HttpRequestManager(context).sendMessage(
                    title.ifBlank { messageTitle },
                    content.ifBlank { "未监听到打卡成功的通知，请手动登录检查" }
                )
            }

            1 -> {
                EmailManager(context).sendEmail(
                    title.ifBlank { messageTitle },
                    content.ifBlank { "未监听到打卡成功的通知，请手动登录检查" },
                    false
                )
            }
        }
    }

    private fun sendAttachmentMessage(title: String, content: String, filePath: String) {
        val context = appContext ?: return
        val messageTitle = SaveKeyValues.getValue(
            Constant.MESSAGE_TITLE_KEY, "打卡结果通知"
        ) as String
        val channelType = SaveKeyValues.getValue(Constant.CHANNEL_TYPE_KEY, -1) as Int
        when (channelType) {
            0 -> RetrofitImageSender.send(context, filePath)
            1 -> EmailManager(context).sendAttachmentEmail(
                title.ifBlank { messageTitle },
                appendDeviceInfo(context, content),
                filePath,
                false
            )
        }
    }

    private fun appendDeviceInfo(context: Context, content: String): String {
        val battery = context.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return buildString {
            appendLine(content)
            appendLine("当前日期：${System.currentTimeMillis().timestampToDate()}")
            appendLine("当前电量：${if (battery >= 0) "$battery%" else "未知"}")
            append("版本号：${BuildConfig.VERSION_NAME}")
        }
    }

    private fun isApplicationExist(context: Context, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun ensureEventRegistered() {
        if (!eventRegistered) {
            EventBus.getDefault().register(this)
            eventRegistered = true
        }
    }

    private fun updateTaskStartedState(started: Boolean) {
        taskStarted = started
        MainActivity.isTaskStarted = started
        SaveKeyValues.putValue(Constant.TASK_RUNNING_STATE_KEY, started)
    }
}
