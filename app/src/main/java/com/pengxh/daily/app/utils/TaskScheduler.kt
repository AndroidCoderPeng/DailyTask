package com.pengxh.daily.app.utils

import android.os.SystemClock
import android.util.Log
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.extensions.resolveExecutionTime
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/**
 * 任务调度器
 */
object TaskScheduler {
    // 单个任务的执行结果
    private enum class TaskOutcome { SUCCESS, TIMEOUT, STOPPED }

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    /**
     * UI 文本事件（tipsView / adapter 高亮），不参与按钮逻辑
     * */
    private val _tipsEvent = MutableSharedFlow<TipsEvent>(extraBufferCapacity = 1)
    val tipsEvent = _tipsEvent.asSharedFlow()

    /**
     * 超时后回到主页信号（TaskScheduler → MainActivity）
     * */
    private val _returnToApp = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val returnToApp = _returnToApp.asSharedFlow()

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /**
     * 打卡信号：外部 notifyClockIn() 触发，解除 select{} 阻塞
     * */
    @Volatile
    private var clockInDeferred: CompletableDeferred<Unit>? = null
    @Volatile
    private var clockInWaitStartedAtMillis: Long? = null
    @Volatile
    private var clockInWaitDeadlineMillis: Long? = null

    private var lastProcessedDate: LocalDate? = null
    private val resetWaitWakeupChannel = Channel<Unit>(Channel.CONFLATED)

    // 单次异常后最大自动重试次数，超过则彻底停止并告警，避免永久性错误疯狂重试
    private const val MAX_AUTO_RECOVERY_COUNT = 3

    // 异常重试退避时长，给外部条件（网络/数据库/投影服务）恢复的时间
    private const val AUTO_RECOVERY_DELAY_MILLIS = 30_000L

    fun notifyResetWaiters() {
        resetWaitWakeupChannel.trySend(Unit)
    }

    /**
     * 由 ForegroundRunningService 调用，注入协程作用域
     */
    fun attach(serviceScope: CoroutineScope, resumeTask: Boolean) {
        scope?.cancel()
        scope = serviceScope
        if (resumeTask) {
            _isRunning.value = false
            startTask()
        }
    }

    fun isRunning(): Boolean {
        return _isRunning.value
    }

    /**
     * 启动每日任务调度
     * 时序：防重复 → 检查协程作用域 → 判断周末/节假日 → 构建排程 → 启动核心循环
     */
    fun startTask(onStarted: ((Boolean) -> Unit)? = null): Boolean {
        if (_isRunning.value) {
            LogFileManager.writeLog("任务已在执行中，忽略重复启动")
            onStarted?.invoke(true)
            return true
        }

        val currentScope = scope
        if (currentScope == null) {
            LogFileManager.writeLog("TaskScheduler scope 未初始化")
            onStarted?.invoke(false)
            return false
        }

        _isRunning.value = true
        AppConfig.saveBoolean(Constant.TASK_SCHEDULER_RUNNING_KEY, true)

        var startReported = false
        fun reportStart(success: Boolean) {
            if (!startReported) {
                startReported = true
                onStarted?.invoke(success)
            }
        }

        // 最后一道防线：任何逃离主循环 try/catch 的异常都在此收口，
        // 保证"调度器挂了"这件事一定被用户感知，而不是静默消失
        val crashHandler = CoroutineExceptionHandler { _, throwable ->
            notifySchedulerError("任务调度器发生未捕获异常并已停止，今日打卡可能无法完成：$throwable", true)
        }

        val tempJob = currentScope.launch(crashHandler) {
            var recoveryCount = 0
            while (isActive) {
                try {
                    val today = LocalDate.now()

                    // 今天已经处理过了，不再重复
                    if (lastProcessedDate == today) {
                        reportStart(true)
                        LogFileManager.writeLog("今日已处理，等待下一次重置")
                        if (isActive) waitUntilNextReset()
                        continue
                    }

                    var completionMessage: String?
                    var emitCompletionEvent = true
                    val skipReason = getTodaySkipReason()
                    if (skipReason != null) {
                        reportStart(true)
                        _tipsEvent.tryEmit(TipsEvent.Skip(skipReason))
                        completionMessage = "今日为${skipReason.label}，任务已跳过"
                        ForegroundRunningService.emitNotificationText(completionMessage)
                        emitCompletionEvent = false
                    } else {
                        val schedule = buildTodaySchedule()
                        if (schedule.isEmpty()) {
                            LogFileManager.writeLog("任务列表为空，停止调度")
                            return@launch
                        }

                        reportStart(true)
                        LogFileManager.writeLog("开始执行每日任务，共 ${schedule.size} 个")
                        completionMessage = executeSchedule(schedule) ?: return@launch
                    }

                    lastProcessedDate = today
                    AppConfig.saveString(Constant.LAST_RESET_DATE_KEY, today.toString())

                    // 今天结束，睡到明天
                    if (isActive) waitUntilNextReset(completionMessage, emitCompletionEvent)

                    // 完整跑完一轮，说明已恢复正常，清零自愈计数
                    recoveryCount = 0
                } catch (ce: CancellationException) {
                    // 主动取消（停止按钮 / 服务销毁）不是故障，必须原样抛出
                    throw ce
                } catch (throwable: Throwable) {
                    LogFileManager.writeLog("任务调度异常：$throwable")
                    if (recoveryCount >= MAX_AUTO_RECOVERY_COUNT) {
                        notifySchedulerError(
                            "任务调度连续异常，自动恢复 $MAX_AUTO_RECOVERY_COUNT 次仍未成功，" +
                                    "今日任务已停止，需人工检查。最后一次错误：$throwable",
                            true
                        )
                        return@launch
                    }

                    recoveryCount++
                    notifySchedulerError(
                        "任务调度发生异常，将在 ${AUTO_RECOVERY_DELAY_MILLIS / 1000}s 后自动重试" +
                                "（第 $recoveryCount/$MAX_AUTO_RECOVERY_COUNT 次）。错误：$throwable",
                        false
                    )
                    delay(AUTO_RECOVERY_DELAY_MILLIS)
                }
            }
        }
        job = tempJob
        tempJob.invokeOnCompletion {
            if (job === tempJob) {
                job = null
                _isRunning.value = false
                AppConfig.saveBoolean(Constant.TASK_SCHEDULER_RUNNING_KEY, false)
            }
            reportStart(false)
        }
        return true
    }

    /**
     * 获取当日 flag
     * */
    fun getDayFlag(): String {
        return classifyDay(LocalDate.now()).label
    }

    /**
     * 链式任务主循环
     * for 循环保证顺序执行，每个任务经历三个阶段：
     *   阶段1 - delay(到任务时间) + 通知栏秒级倒计时
     *   阶段2 - openApplication() + select{超时|打卡} 竞态等待
     *   阶段3 - 推进到下一个任务（或全部完成 emit Completed）
     */
    private suspend fun CoroutineScope.executeSchedule(schedule: List<ScheduledTask>): String? {
        var successCount = 0
        var failureCount = 0
        var skippedCount = 0
        var screenshotAuthInvalid = false

        for (task in schedule) {
            val now = System.currentTimeMillis()

            // 任务时间已过，跳过
            if (task.actualTimeMillis <= now) {
                skippedCount++
                LogFileManager.writeLog(
                    "第 ${task.displayIndex} 个任务已过期（计划=${task.plannedTime}，" +
                            "实际=${task.actualTime}），跳过"
                )
                continue
            }

            // 非钉钉目标仅支持截屏来源，投影失效则剩余任务均无法获取打卡结果，跳过并说明根因
            if (Constant.getTargetApp() != Constant.DING_DING &&
                AppConfig.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX) != 1
            ) {
                val remainingCount = schedule.size - task.displayIndex + 1
                skippedCount += remainingCount
                screenshotAuthInvalid = true
                LogFileManager.writeLog(
                    "非钉钉目标仅支持截屏来源，截屏授权已失效，跳过剩余 $remainingCount 个任务"
                )
                MessageDispatcher.sendMessage(
                    "任务跳过通知",
                    "截屏授权已失效，剩余 $remainingCount 个任务已跳过，需人工重新授权后恢复"
                )
                break
            }

            // ====== 阶段 1：倒计时等待 ======
            val delayMs = task.actualTimeMillis - now
            _tipsEvent.tryEmit(
                TipsEvent.Executing(
                    task.displayIndex,
                    schedule.size,
                    task.actualTime,
                    task.plannedTime
                )
            )

            LogFileManager.writeLog(
                "调度第 ${task.displayIndex} 个任务，" +
                        "计划时间=${task.plannedTime}，" +
                        "实际时间=${task.actualTime}，" +
                        "延迟=${delayMs / 1000}s"
            )

            updateCountdownWithNotification(
                delayMs,
                tickIntervalMillis = { remaining ->
                    if (remaining < 60_000L) 1_000L else 10_000L
                }
            ) { remaining ->
                val seconds = (remaining / 1000).toInt()
                ForegroundRunningService.emitNotificationText(
                    "${seconds.formatTime()}后执行第${task.displayIndex}个任务"
                )
            }

            // ====== 阶段 2：打开目标 App，等待打卡或超时 ======
            val timeoutSeconds = AppConfig.loadInt(
                Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
            )

            // 竞态保护：select 只取先完成的分支，另一个自动取消
            var hasCaptured = false
            var captureDeferred: CompletableDeferred<String?>? = null
            val clockInSignal = CompletableDeferred<Unit>()
            var timeoutJob: Job? = null
            val schedulerScope = this

            // 设备执行权：同一时刻只允许一条流程驱动目标 App/悬浮窗/截屏。
            // 谁先到谁先执行，后到的排队等待（互斥，不抢占）。
            val outcome = AutomationGate.withDevice(AutomationGate.Owner.SCHEDULED) {
                clockInWaitStartedAtMillis = System.currentTimeMillis()
                clockInWaitDeadlineMillis = null
                clockInDeferred = clockInSignal
                var result = TaskOutcome.TIMEOUT
                try {
                    if (!DailyTaskApplication.get().openApplication()) {
                        requestStopDueToError("无法打开目标应用，任务已停止")
                        result = TaskOutcome.STOPPED
                    } else {
                        clockInWaitDeadlineMillis =
                            System.currentTimeMillis() + timeoutSeconds * 1000L

                        val activeTimeoutJob = schedulerScope.launch {
                            updateCountdownWithNotification(timeoutSeconds * 1000L) { remaining ->
                                val tick = (remaining / 1000).toInt()
                                FloatingWindowController.updateTime(tick)

                                // 最后 5 秒兜底截屏（只触发一次）
                                if (timeoutSeconds > 5 && tick <= 5 && !hasCaptured) {
                                    val resultSource = AppConfig.loadInt(
                                        Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX
                                    )
                                    if (resultSource == 1) {
                                        hasCaptured = true
                                        captureDeferred = CaptureImageService.requestCaptureScreen()
                                    }
                                }
                            }
                        }
                        timeoutJob = activeTimeoutJob

                        result = select {
                            // 分支 A：超时
                            activeTimeoutJob.onJoin { TaskOutcome.TIMEOUT }

                            // 分支 B：打卡成功
                            clockInSignal.onAwait { TaskOutcome.SUCCESS }
                        }
                    }
                } finally {
                    timeoutJob?.cancel()
                    if (clockInDeferred === clockInSignal) {
                        clockInDeferred = null
                        clockInWaitStartedAtMillis = null
                        clockInWaitDeadlineMillis = null
                    }
                }

                // 超时路径——打卡失败，回到主页 + 兜底通知 + 继续下一个任务
                // （在执行权内完成，避免与远程指令抢同一帧）
                if (result == TaskOutcome.TIMEOUT) {
                    _returnToApp.tryEmit(Unit)

                    // 发送兜底截图给用户
                    if (hasCaptured) {
                        // Deferred 内部已有 5s 超时兜底，await() 不会无限挂起
                        when (val imagePath = captureDeferred?.await()) {
                            null -> MessageDispatcher.sendMessage(
                                "任务执行结果通知", "截屏请求超时，无法获取截图"
                            )

                            "" -> MessageDispatcher.sendMessage(
                                "任务执行结果通知", "截屏失败，无法获取图像"
                            )

                            else -> MessageDispatcher.sendAttachmentMessage(
                                "任务执行结果通知", "任务执行结果见附件", imagePath
                            )
                        }
                    } else {
                        // 通知模式：无截图，纯文本提醒
                        // 期间若有远程打卡指令，本次未监听到成功属正常（已被指令代打卡）
                        val remoteHint = if (AutomationGate.hasRecentRemoteClockIn()) {
                            "（由远程打卡指令处理），请以指令结果为准"
                        } else {
                            "，请手动检查是否打卡成功"
                        }
                        MessageDispatcher.sendMessage(
                            "任务执行结果通知", "本次任务未监听到打卡成功$remoteHint"
                        )
                    }
                }
                result
            }

            if (outcome == TaskOutcome.STOPPED) {
                return null
            }

            // ====== 阶段 3：回到主界面，处理结果 ======
            if (outcome == TaskOutcome.SUCCESS) {
                successCount++
            } else {
                failureCount++
            }
        }

        // ====== 全部完成 ======
        val message = when {
            successCount + failureCount == 0 && skippedCount == 0 -> "无任务可供执行"
            successCount + failureCount == 0 && screenshotAuthInvalid ->
                "截屏授权已失效，剩余 $skippedCount 个任务已跳过，需人工重新授权后恢复截屏打卡"
            successCount + failureCount == 0 ->
                "今日所有任务均已过期，跳过（$skippedCount 个），无需执行"
            skippedCount > 0 ->
                "今日任务处理完毕（成功 $successCount 个，失败 $failureCount 个，跳过 $skippedCount 个）"
            else -> "今日任务处理完毕（成功 $successCount 个，失败 $failureCount 个）"
        }
        LogFileManager.writeLog(message)
        ForegroundRunningService.emitNotificationText(message)
        return message
    }

    /**
     * 调试用：非 null 时跳过真实计算，直接使用指定秒数
     * 生产环境保持 null
     */
    @Volatile
    var debugWaitSeconds: Long? = null

    private const val RESET_CONFIG_CHECK_INTERVAL_MILLIS = 10 * 60 * 1000L

    /**
     * 等待到下一个每日重置时间
     */
    private suspend fun waitUntilNextReset(
        completionMessage: String? = null,
        emitCompletionEvent: Boolean = true
    ) {
        debugWaitSeconds?.let {
            delay(it * 1000)
            return
        }

        val initialResetHour = AppConfig.loadInt(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        )
        val initialTargetMillis = nextResetTimeMillis(initialResetHour)
        val initialWaitSeconds =
            ((initialTargetMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
        LogFileManager.writeLog("等待 ${initialWaitSeconds}s 后进入下一个任务周期")

        // 只发一次静态通知，不每秒刷新
        if (emitCompletionEvent) {
            _tipsEvent.tryEmit(TipsEvent.Completed(completionMessage))
        }
        ForegroundRunningService.emitNotificationText(
            "${completionMessage ?: "今日任务已全部执行完毕"}，等待下次任务"
        )

        var targetResetHour = initialResetHour
        var targetMillis = initialTargetMillis
        var alarmScheduledForHour: Int? = null
        while (true) {
            val resetHour = AppConfig.loadInt(
                Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
            )
            if (resetHour != targetResetHour) {
                targetResetHour = resetHour
                targetMillis = nextResetTimeMillis(resetHour)
            }
            if (alarmScheduledForHour != resetHour) {
                DailyResetAlarmScheduler.schedule(DailyTaskApplication.get())
                alarmScheduledForHour = resetHour
            }

            val remainingMillis = (targetMillis - System.currentTimeMillis()).coerceAtLeast(1)
            withTimeoutOrNull(minOf(remainingMillis, RESET_CONFIG_CHECK_INTERVAL_MILLIS)) {
                resetWaitWakeupChannel.receive()
            }

            val currentResetHour = AppConfig.loadInt(
                Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
            )
            if (currentResetHour != resetHour) {
                continue
            }
            if (System.currentTimeMillis() >= targetMillis) {
                return
            }
        }
    }

    /**
     * 打卡成功通知
     * 仅接受当前任务等待窗口内的通知，避免迟到通知完成后续任务。
     */
    fun notifyClockIn(postedAtMillis: Long): Boolean {
        val signal = clockInDeferred ?: return false
        val windowStartMillis = clockInWaitStartedAtMillis ?: return false
        val windowDeadlineMillis = clockInWaitDeadlineMillis
        if (postedAtMillis < windowStartMillis ||
            (windowDeadlineMillis != null && postedAtMillis > windowDeadlineMillis)
        ) {
            LogFileManager.writeLog("忽略等待窗口外的打卡成功通知：$postedAtMillis")
            return false
        }
        // 远程打卡指令已代打卡：其"成功"通知不计入定时任务
        if (AutomationGate.belongsToRemoteDrive(postedAtMillis)) {
            LogFileManager.writeLog("忽略远程打卡指令产生的成功通知：$postedAtMillis")
            return false
        }
        return signal.complete(Unit)
    }

    fun stopTask() {
        if (!_isRunning.value) {
            LogFileManager.writeLog("任务未运行，无需停止")
            return
        }

        LogFileManager.writeLog("停止执行每日任务")
        job?.cancel()
        job = null
        _isRunning.value = false
        AppConfig.saveBoolean(Constant.TASK_SCHEDULER_RUNNING_KEY, false)
        ForegroundRunningService.emitNotificationText("为保证程序正常运行，请勿移除此通知")
    }

    /**
     * 发送调度异常告警，并确保"告警动作本身"不会二次崩溃。
     *
     * 无人值守的关键：调度挂了必须让用户知道。因此日志、通知栏、消息渠道三条路都尝试，
     * 且每一路各自 try/catch 互不牵连——即使渠道或文件 IO 故障，也不会因此把主循环再炸一次。
     *
     * @param fatal true 表示调度已彻底停止（不再自愈）
     */
    private fun notifySchedulerError(message: String, fatal: Boolean) {
        try {
            LogFileManager.writeLog(message)
        } catch (e: Exception) {
            Log.e("TaskScheduler", "写入调度日志失败", e)
        }
        try {
            ForegroundRunningService.emitNotificationText(
                if (fatal) "任务调度已异常停止，请检查" else "任务调度异常，正在重试"
            )
        } catch (e: Exception) {
            Log.e("TaskScheduler", "更新通知栏失败", e)
        }
        try {
            MessageDispatcher.sendMessage(
                if (fatal) "任务异常告警（已停止）" else "任务异常告警", message
            )
        } catch (e: Exception) {
            Log.e("TaskScheduler", "发送异常告警失败", e)
        }
    }

    /**
     * 因外部错误请求停止（目标 App 未安装、启动失败等）
     *
     * 与 stopTask() 的区别：
     *   stopTask()     — 用户主动点击"停止"，发消息通知
     *   requestStopDueToError() — 系统错误停止，不发消息通知，只重置调度器
     */
    fun requestStopDueToError(reason: String) {
        LogFileManager.writeLog("因错误请求停止：$reason")
        val shouldNotify = _isRunning.value
        job?.cancel()
        job = null
        _isRunning.value = false
        AppConfig.saveBoolean(Constant.TASK_SCHEDULER_RUNNING_KEY, false)
        if (shouldNotify) {
            ForegroundRunningService.emitNotificationText(reason)
            MessageDispatcher.sendMessage("任务停止通知", reason)
        }
    }

    /**
     * 自校准倒计时 tick，支持 UI 回调。
     * 使用 elapsedRealtime 确保休眠唤醒后剩余时间准确。
     */
    private suspend fun CoroutineScope.updateCountdownWithNotification(
        totalMs: Long,
        tickIntervalMillis: (remainingMs: Long) -> Long = { 1_000L },
        onTick: (remainingMs: Long) -> Unit
    ) {
        val target = SystemClock.elapsedRealtime() + totalMs
        while (isActive) {
            val remaining = target - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            onTick(remaining)
            val step = minOf(tickIntervalMillis(remaining), remaining).coerceAtLeast(1)
            delay(step)
        }
    }

    private fun getTodaySkipReason(): DayFlag? {
        val skipEnabled = AppConfig.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)
        if (!skipEnabled) return null

        val dayFlag = classifyDay(LocalDate.now())
        when (dayFlag) {
            DayFlag.SubstituteWorkday -> {
                LogFileManager.writeLog("今日为调休补班日，正常执行任务")
            }

            DayFlag.Holiday -> {
                LogFileManager.writeLog("今日为法定节假日，跳过任务")
            }

            DayFlag.RestDay -> {
                LogFileManager.writeLog("今日为休息日，跳过任务")
            }

            DayFlag.Workday -> Unit
        }
        return dayFlag.takeIf { it.shouldSkip }
    }

    private fun classifyDay(date: LocalDate): DayFlag {
        return when {
            ChinaHolidayManager.isWorkday(date) -> DayFlag.SubstituteWorkday
            ChinaHolidayManager.isHoliday(date) -> DayFlag.Holiday
            CustomWorkdayManager.isWeekdayRestDay(date) -> DayFlag.RestDay
            else -> DayFlag.Workday
        }
    }

    /**
     * 从数据库加载所有任务，计算出当日实际执行时间，按时间排序
     * */
    private suspend fun buildTodaySchedule(): List<ScheduledTask> {
        val allTasks = withContext(Dispatchers.IO) {
            DatabaseWrapper.loadAllTask()
        }
        if (allTasks.isEmpty()) return emptyList()

        val baseMillis = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        return allTasks.map { task ->
            val actualTime = task.resolveExecutionTime()
            val timeParts = actualTime.split(":").map { it.toInt() }
            val actualMillis = baseMillis +
                    timeParts[0] * 3_600_000L +
                    timeParts[1] * 60_000L +
                    timeParts[2] * 1_000L
            Triple(task, actualTime, actualMillis)
        }.sortedBy { it.third }.mapIndexed { index, (task, actualTime, actualMillis) ->
            ScheduledTask(task, index + 1, task.time, actualTime, actualMillis)
        }
    }

    private fun nextResetTimeMillis(resetHour: Int): Long {
        val now = Calendar.getInstance()
        val target = now.clone() as Calendar
        target.set(Calendar.HOUR_OF_DAY, resetHour)
        target.set(Calendar.MINUTE, 0)
        target.set(Calendar.SECOND, 0)
        target.set(Calendar.MILLISECOND, 0)

        if (now.timeInMillis >= target.timeInMillis) {
            target.add(Calendar.DATE, 1)
        }

        return target.timeInMillis
    }

    private data class ScheduledTask(
        val task: DailyTaskBean,
        val displayIndex: Int,
        val plannedTime: String,
        val actualTime: String,
        val actualTimeMillis: Long
    )
}
