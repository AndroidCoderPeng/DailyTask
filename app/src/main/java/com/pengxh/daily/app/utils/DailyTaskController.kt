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
import com.pengxh.daily.app.extensions.getTaskIndex
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.ui.KeyguardDismissActivity
import com.pengxh.daily.app.ui.MainActivity
import com.pengxh.kt.lite.extensions.timestampToDate
import com.pengxh.kt.lite.utils.SaveKeyValues
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

object DailyTaskController : TaskScheduler.TaskStateListener {

    private const val HEALTH_WARNING_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val KEYGUARD_DISMISS_WAIT_TIMEOUT_MS = 6000L
    private const val ACTIVE_WINDOW_RETRY_DELAY_MS = 3000L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val timeoutTimerManager by lazy { TimeoutTimerManager(mainHandler) }
    private val taskScheduler by lazy { TaskScheduler(mainHandler, this) }

    private data class PendingKeyguardDismissExecution(
        val context: Context,
        val trackTaskResult: Boolean,
        val remoteScreenshot: Boolean,
        val advanceSchedulerOnResult: Boolean
    )

    private var appContext: Context? = null
    private var eventRegistered = false
    private var imagePath = ""
    private var hasCaptured = false
    private var lastHealthWarningMessage = ""
    private var lastHealthWarningAt = 0L
    private var keyguardDismissRetryPending = false
    private var keyguardDismissRetryRunnable: Runnable? = null
    private var pendingKeyguardDismissExecution: PendingKeyguardDismissExecution? = null
    private var pendingDailyExecutionRetryRunnable: Runnable? = null

    @Volatile
    private var activeTaskExecutionStartedAt = 0L

    @Volatile
    private var activeExecutionAdvancesScheduler = false

    @Volatile
    private var taskStarted = false

    @Volatile
    private var deferredSchedulerAdvanceAfterManualExecution = false

    @Volatile
    private var remoteScreenshotTimerRunning = false

    fun attachCountDownTimerService(service: CountDownTimerService) {
        appContext = service.applicationContext
        ensureEventRegistered()
        taskScheduler.setCountDownTimerService(service)
    }

    fun detachCountDownTimerService(service: CountDownTimerService) {
        if (appContext === service.applicationContext) {
            taskScheduler.detachCountDownTimerService(service)
        }
    }

    fun isTaskStarted(): Boolean {
        val savedState = SaveKeyValues.getValue(
            Constant.TASK_RUNNING_STATE_KEY, false
        ) as Boolean
        return taskScheduler.isTaskStarted() || taskStarted || savedState
    }

    fun isExecutionWindowActive(): Boolean {
        return timeoutTimerManager.isRunning() ||
            keyguardDismissRetryPending ||
            remoteScreenshotTimerRunning
    }

    fun startTask(context: Context) {
        appContext = context.applicationContext
        ensureEventRegistered()
        if (taskScheduler.isTaskStarted()) {
            taskScheduler.executeNextTask()
            return
        }

        if (hasPendingTaskForToday() && skipTaskOnChinaHolidayIfNeeded()) {
            return
        }

        taskScheduler.startTask()
    }

    fun stopTask() {
        cancelPendingKeyguardDismissRetry()
        taskScheduler.stopTask()
    }

    fun executeNextTask() {
        taskScheduler.executeNextTask()
    }

    fun handleTaskSuccess(
        context: Context,
        successPostTime: Long = System.currentTimeMillis()
    ): Boolean {
        appContext = context.applicationContext
        if (!timeoutTimerManager.isRunning()) {
            LogFileManager.writeLog("收到重复或过期的成功通知，忽略本次任务推进")
            return false
        }
        if (activeTaskExecutionStartedAt > 0 && successPostTime < activeTaskExecutionStartedAt) {
            LogFileManager.writeLog("收到早于当前执行窗口的成功通知，忽略本次任务推进")
            return false
        }
        val shouldAdvanceScheduler = activeExecutionAdvancesScheduler
        val shouldAdvanceDeferredScheduler = consumeDeferredSchedulerAdvance()
        timeoutTimerManager.cancelTimeoutTimer()
        clearTaskExecutionState()
        backToMainActivity()
        if (shouldAdvanceScheduler || shouldAdvanceDeferredScheduler) {
            if (shouldAdvanceDeferredScheduler && !shouldAdvanceScheduler) {
                LogFileManager.writeLog("远程手动执行成功，作为已到点每日任务结果继续调度")
            }
            taskScheduler.executeNextTask(markCurrentFinished = true)
        } else {
            LogFileManager.writeLog("远程手动执行已收到成功通知，不推进每日任务调度")
        }
        return true
    }

    fun setMaskVisible(context: Context, visible: Boolean) {
        appContext = context.applicationContext
        SaveKeyValues.putValue(Constant.IN_APP_MASK_VISIBLE_KEY, visible)
        startMainActivity(context.applicationContext)
        val event = if (visible) {
            ApplicationEvent.ShowMaskView
        } else {
            ApplicationEvent.HideMaskView
        }
        EventBus.getDefault().post(event)
        mainHandler.postDelayed({ EventBus.getDefault().post(event) }, 500)
    }

    fun isMaskExpectedVisible(): Boolean {
        return SaveKeyValues.getValue(Constant.IN_APP_MASK_VISIBLE_KEY, false) as Boolean
    }

    fun openTargetApplication(
        context: Context,
        trackTaskResult: Boolean,
        remoteScreenshot: Boolean = false,
        advanceSchedulerOnResult: Boolean = trackTaskResult
    ) {
        openTargetApplicationInternal(
            context,
            trackTaskResult,
            remoteScreenshot,
            advanceSchedulerOnResult,
            allowKeyguardDismissAttempt = true
        )
    }

    private fun openTargetApplicationInternal(
        context: Context,
        trackTaskResult: Boolean,
        remoteScreenshot: Boolean,
        advanceSchedulerOnResult: Boolean,
        allowKeyguardDismissAttempt: Boolean
    ) {
        appContext = context.applicationContext
        ensureEventRegistered()
        val stopTaskOnFailure = shouldStopTaskOnOpenFailure(
            trackTaskResult,
            remoteScreenshot,
            advanceSchedulerOnResult
        )
        if (trackTaskResult && isExecutionWindowActive()) {
            val message = "已有任务正在执行或准备执行，暂不覆盖当前执行窗口"
            LogFileManager.writeLog(message)
            if (advanceSchedulerOnResult && canAttachSchedulerAdvanceToActiveWindow()) {
                deferredSchedulerAdvanceAfterManualExecution = true
                LogFileManager.writeLog("每日任务到点时手动执行窗口正在运行，将使用手动执行结果继续每日调度")
            } else if (advanceSchedulerOnResult && shouldRetrySchedulerAfterActiveWindow()) {
                scheduleDailyExecutionRetry(
                    context,
                    trackTaskResult,
                    remoteScreenshot,
                    advanceSchedulerOnResult,
                    allowKeyguardDismissAttempt
                )
            }
            if (!advanceSchedulerOnResult) {
                sendChannelMessage("远程执行状态通知", "$message，请稍后再发送手动打卡口令")
            }
            return
        }
        val targetApp = Constant.getTargetApp()
        if (!isApplicationExist(context, targetApp)) {
            handleOpenFailure("未安装指定的目标软件，无法执行任务", stopTaskOnFailure)
            return
        }

        if (tryDismissKeyguardBeforeExecution(
                context,
                trackTaskResult,
                remoteScreenshot,
                advanceSchedulerOnResult,
                allowKeyguardDismissAttempt
            )
        ) {
            return
        }

        if (!checkExecutionHealth(context, trackTaskResult, remoteScreenshot, stopTaskOnFailure)) {
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
            handleOpenFailure("未找到目标应用入口，无法执行任务", stopTaskOnFailure)
            return
        }

        val info = activities.first()
        intent.component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
        var startedTaskResultTracking = false
        if (trackTaskResult) {
            startTaskTimeoutTimer(advanceSchedulerOnResult)
            startedTaskResultTracking = true
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            if (startedTaskResultTracking) {
                cancelTaskExecutionTracking()
            }
            handleOpenFailure(
                "未打开目标应用：${e.message ?: e.javaClass.simpleName}",
                stopTaskOnFailure
            )
            return
        }

        if (remoteScreenshot) {
            startRemoteScreenshotTimer()
        }
    }

    private fun tryDismissKeyguardBeforeExecution(
        context: Context,
        trackTaskResult: Boolean,
        remoteScreenshot: Boolean,
        advanceSchedulerOnResult: Boolean,
        allowKeyguardDismissAttempt: Boolean
    ): Boolean {
        if (!allowKeyguardDismissAttempt) {
            return false
        }
        val retryContext = context.applicationContext
        if (!TaskHealthChecker.canAttemptDismissKeyguard(retryContext)) {
            return false
        }
        if (keyguardDismissRetryPending) {
            LogFileManager.writeLog("锁屏唤醒解锁已在进行中，忽略重复请求")
            return true
        }

        keyguardDismissRetryPending = true
        pendingKeyguardDismissExecution = PendingKeyguardDismissExecution(
            retryContext,
            trackTaskResult,
            remoteScreenshot,
            advanceSchedulerOnResult
        )
        LogFileManager.writeLog("检测到无安全密码锁屏，先尝试唤醒解锁后再执行任务")
        try {
            Intent(retryContext, KeyguardDismissActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                retryContext.startActivity(this)
            }
        } catch (e: Exception) {
            clearPendingKeyguardDismissRetry()
            LogFileManager.writeLog("锁屏唤醒页面启动失败：${e.message ?: e.javaClass.simpleName}")
            return false
        }
        val retryRunnable = Runnable {
            continueAfterKeyguardDismiss(false, "锁屏唤醒结果未返回")
        }
        keyguardDismissRetryRunnable = retryRunnable
        mainHandler.postDelayed(retryRunnable, KEYGUARD_DISMISS_WAIT_TIMEOUT_MS)
        return true
    }

    private fun cancelPendingKeyguardDismissRetry() {
        keyguardDismissRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        clearPendingKeyguardDismissRetry()
    }

    private fun clearPendingKeyguardDismissRetry() {
        keyguardDismissRetryRunnable = null
        keyguardDismissRetryPending = false
        pendingKeyguardDismissExecution = null
    }

    private fun continueAfterKeyguardDismiss(success: Boolean, message: String) {
        val pending = pendingKeyguardDismissExecution ?: return
        keyguardDismissRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        keyguardDismissRetryRunnable = null
        keyguardDismissRetryPending = false
        pendingKeyguardDismissExecution = null

        LogFileManager.writeLog(message)
        if (!success) {
            LogFileManager.writeLog("锁屏唤醒未确认成功，复查系统锁屏状态后再决定是否继续")
        }
        val stillLocked = TaskHealthChecker.isKeyguardLocked(pending.context)
        if (stillLocked) {
            val failureMessage = buildString {
                append(message)
                if (stillLocked) {
                    append("；设备仍处于锁屏状态，已取消本次自动执行")
                }
            }
            val stopTaskOnFailure = shouldStopTaskOnOpenFailure(
                pending.trackTaskResult,
                pending.remoteScreenshot,
                pending.advanceSchedulerOnResult
            )
            handleOpenFailure(failureMessage, stopTaskOnFailure)
            return
        }
        if (
            pending.advanceSchedulerOnResult &&
            pending.trackTaskResult &&
            !pending.remoteScreenshot &&
            !isTaskStarted()
        ) {
            LogFileManager.writeLog("每日任务已停止，取消锁屏唤醒后的执行重试")
            return
        }
        openTargetApplicationInternal(
            pending.context,
            pending.trackTaskResult,
            pending.remoteScreenshot,
            pending.advanceSchedulerOnResult,
            allowKeyguardDismissAttempt = false
        )
    }

    private fun startRemoteScreenshotTimer() {
        imagePath = ""
        hasCaptured = false
        remoteScreenshotTimerRunning = true
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
                remoteScreenshotTimerRunning = false
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

    private fun canAttachSchedulerAdvanceToActiveWindow(): Boolean {
        if (timeoutTimerManager.isRunning()) {
            return !activeExecutionAdvancesScheduler
        }
        val pending = pendingKeyguardDismissExecution ?: return false
        return keyguardDismissRetryPending &&
            pending.trackTaskResult &&
            !pending.remoteScreenshot &&
            !pending.advanceSchedulerOnResult
    }

    private fun shouldRetrySchedulerAfterActiveWindow(): Boolean {
        val pending = pendingKeyguardDismissExecution
        return remoteScreenshotTimerRunning ||
            (keyguardDismissRetryPending && pending?.trackTaskResult == false)
    }

    private fun scheduleDailyExecutionRetry(
        context: Context,
        trackTaskResult: Boolean,
        remoteScreenshot: Boolean,
        advanceSchedulerOnResult: Boolean,
        allowKeyguardDismissAttempt: Boolean
    ) {
        pendingDailyExecutionRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        val retryContext = context.applicationContext
        val retryRunnable = Runnable {
            pendingDailyExecutionRetryRunnable = null
            if (!isTaskStarted()) {
                LogFileManager.writeLog("每日任务已停止，取消临时操作后的执行重试")
                return@Runnable
            }
            LogFileManager.writeLog("临时操作结束后重试当前每日任务")
            openTargetApplicationInternal(
                retryContext,
                trackTaskResult,
                remoteScreenshot,
                advanceSchedulerOnResult,
                allowKeyguardDismissAttempt
            )
        }
        pendingDailyExecutionRetryRunnable = retryRunnable
        mainHandler.postDelayed(retryRunnable, ACTIVE_WINDOW_RETRY_DELAY_MS)
        LogFileManager.writeLog("当前仅有临时远程操作在执行，每日任务将在稍后重试")
    }

    private fun startTaskTimeoutTimer(advanceSchedulerOnResult: Boolean) {
        imagePath = ""
        activeTaskExecutionStartedAt = System.currentTimeMillis()
        activeExecutionAdvancesScheduler = advanceSchedulerOnResult
        timeoutTimerManager.startTimeoutTimer {
            val shouldAdvanceScheduler = activeExecutionAdvancesScheduler
            val shouldAdvanceDeferredScheduler = consumeDeferredSchedulerAdvance()
            backToMainActivity()

            val resultSource = SaveKeyValues.getValue(Constant.RESULT_SOURCE_KEY, 0) as Int
            val title = if (shouldAdvanceScheduler || shouldAdvanceDeferredScheduler) {
                "任务执行失败通知"
            } else {
                "远程执行失败通知"
            }
            val suffix = if (shouldAdvanceScheduler || shouldAdvanceDeferredScheduler) {
                "系统将继续调度后续任务"
            } else {
                "每日任务调度未受影响"
            }
            if (resultSource == 0) {
                sendChannelMessage(
                    title,
                    "未收到目标应用的打卡成功通知，本次任务可能未完成；请检查通知监听、目标应用状态或锁屏限制，$suffix"
                )
            } else {
                if (imagePath.isBlank()) {
                    sendChannelMessage(
                        title,
                        "未获取到执行结果截图，无法确认本次任务是否完成；请检查截图服务授权和目标应用状态，$suffix"
                    )
                } else {
                    sendAttachmentMessage("", "打卡完成，结果请查看附件", imagePath)
                }
            }

            clearTaskExecutionState()
            if (shouldAdvanceScheduler || shouldAdvanceDeferredScheduler) {
                if (shouldAdvanceDeferredScheduler && !shouldAdvanceScheduler) {
                    LogFileManager.writeLog("远程手动执行超时，作为已到点每日任务失败结果继续调度")
                }
                taskScheduler.executeNextTask(markCurrentFinished = true)
            } else {
                LogFileManager.writeLog("远程手动执行超时，不推进每日任务调度")
            }
        }
    }

    private fun cancelTaskExecutionTracking() {
        timeoutTimerManager.cancelTimeoutTimer()
        clearTaskExecutionState()
    }

    private fun cancelPendingDailyExecutionRetry() {
        pendingDailyExecutionRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingDailyExecutionRetryRunnable = null
    }

    private fun clearTaskExecutionState() {
        activeTaskExecutionStartedAt = 0L
        activeExecutionAdvancesScheduler = false
    }

    private fun consumeDeferredSchedulerAdvance(): Boolean {
        val shouldAdvance = deferredSchedulerAdvanceAfterManualExecution &&
            taskScheduler.isTaskStarted()
        deferredSchedulerAdvanceAfterManualExecution = false
        return shouldAdvance
    }

    private fun checkExecutionHealth(
        context: Context,
        trackTaskResult: Boolean,
        remoteScreenshot: Boolean,
        stopTaskOnFailure: Boolean
    ): Boolean {
        val result = TaskHealthChecker.checkBeforeExecution(
            context,
            trackTaskResult,
            remoteScreenshot
        )
        if (!result.canContinue()) {
            handleOpenFailure(
                TaskHealthChecker.formatBlockers(result.blockers),
                stopTaskOnFailure
            )
            return false
        }

        if (result.warnings.isNotEmpty()) {
            val message = TaskHealthChecker.formatWarnings(result.warnings)
            if (shouldSendHealthWarning(message)) {
                sendChannelMessage("无人值守自检提醒", message)
            }
            LogFileManager.writeLog(message)
        }
        return true
    }

    private fun shouldSendHealthWarning(message: String): Boolean {
        val now = System.currentTimeMillis()
        val shouldSend = message != lastHealthWarningMessage ||
            now - lastHealthWarningAt > HEALTH_WARNING_INTERVAL_MS
        if (shouldSend) {
            lastHealthWarningMessage = message
            lastHealthWarningAt = now
        }
        return shouldSend
    }

    private fun shouldStopTaskOnOpenFailure(
        trackTaskResult: Boolean,
        remoteScreenshot: Boolean,
        advanceSchedulerOnResult: Boolean
    ): Boolean {
        return advanceSchedulerOnResult &&
            trackTaskResult &&
            !remoteScreenshot &&
            taskScheduler.isTaskStarted()
    }

    private fun hasPendingTaskForToday(): Boolean {
        val taskBeans = DatabaseWrapper.loadAllTask()
        return taskBeans.isNotEmpty() && taskBeans.getTaskIndex() != -1
    }

    private fun skipTaskOnChinaHolidayIfNeeded(): Boolean {
        val enabled = SaveKeyValues.getValue(
            Constant.SKIP_CHINA_HOLIDAY_KEY, false
        ) as Boolean
        if (!enabled) {
            return false
        }

        appContext?.let {
            ChinaHolidayRemoteUpdater.refreshCurrentAndNextYearIfNeeded(it)
        }
        val dayInfo = ChinaHolidayCalendar.evaluateToday()
        if (!dayInfo.shouldSkip) {
            if (!dayInfo.hasOfficialAdjustment) {
                LogFileManager.writeLog(
                    "当前年份未配置中国节假日调休表，今日${dayInfo.date}按${dayInfo.reason}处理"
                )
            }
            return false
        }

        updateTaskStartedState(false)
        timeoutTimerManager.cancelTimeoutTimer()
        clearTaskExecutionState()
        deferredSchedulerAdvanceAfterManualExecution = false
        cancelPendingDailyExecutionRetry()

        val message = buildString {
            append("今日${dayInfo.date}为${dayInfo.reason}，已按“跳过休息日”设置跳过任务")
            if (!dayInfo.hasOfficialAdjustment) {
                append("；当前年份未配置官方调休表，仅按周末规则判断")
            }
        }
        LogFileManager.writeLog("节假日判断来源：${dayInfo.source}")
        LogFileManager.writeLog(message)
        EventBus.getDefault().post(ApplicationEvent.DailyTaskSkipped(message))
        sendChannelMessage("节假日跳过通知", message)
        return true
    }

    private fun handleOpenFailure(message: String, stopTask: Boolean) {
        LogFileManager.writeLog(message)
        val title = if (stopTask) "任务执行失败通知" else "远程操作失败通知"
        sendChannelMessage(title, message)
        if (stopTask) {
            taskScheduler.stopTask()
        } else if (consumeDeferredSchedulerAdvance()) {
            LogFileManager.writeLog("手动执行失败，作为已到点每日任务失败结果继续调度")
            taskScheduler.executeNextTask(markCurrentFinished = true)
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
        cancelPendingKeyguardDismissRetry()
        cancelPendingDailyExecutionRetry()
        clearTaskExecutionState()
        deferredSchedulerAdvanceAfterManualExecution = false
        timeoutTimerManager.cancelTimeoutTimer()
        EventBus.getDefault().post(ApplicationEvent.DailyTaskStopped)
        sendChannelMessage("停止任务通知", "任务停止成功，请及时打开下次任务")
    }

    override fun onTaskCompleted() {
        updateTaskStartedState(false)
        cancelPendingKeyguardDismissRetry()
        cancelPendingDailyExecutionRetry()
        clearTaskExecutionState()
        deferredSchedulerAdvanceAfterManualExecution = false
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
        cancelPendingKeyguardDismissRetry()
        cancelPendingDailyExecutionRetry()
        clearTaskExecutionState()
        deferredSchedulerAdvanceAfterManualExecution = false
        EventBus.getDefault().post(ApplicationEvent.DailyTaskExecutionError(message))
        sendChannelMessage("任务执行出错通知", message)
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun handleApplicationEvent(event: ApplicationEvent) {
        when (event) {
            is ApplicationEvent.CaptureCompleted -> imagePath = event.imagePath
            is ApplicationEvent.KeyguardDismissFinished -> {
                continueAfterKeyguardDismiss(event.success, event.message)
            }

            else -> {}
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
