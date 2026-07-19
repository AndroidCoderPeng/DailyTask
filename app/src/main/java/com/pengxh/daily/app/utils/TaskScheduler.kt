package com.pengxh.daily.app.utils

import android.os.SystemClock
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.extensions.resolveExecutionTime
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/**
 * 任务调度器
 */
object TaskScheduler {
    /**
     * 调度器是否在运行中
     * */
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
    private var clockInDeferred: CompletableDeferred<Unit>? = null

    private var lastProcessedDate: LocalDate? = null

    /**
     * 由 ForegroundRunningService 调用，注入协程作用域
     */
    fun attach(serviceScope: CoroutineScope) {
        scope?.cancel()
        scope = serviceScope
    }

    fun isRunning(): Boolean {
        return _isRunning.value
    }

    /**
     * 启动每日任务调度
     * 时序：防重复 → 检查协程作用域 → 判断周末/节假日 → 构建排程 → 启动核心循环
     */
    fun startTask() {
        if (_isRunning.value) {
            LogFileManager.writeLog("任务已在执行中，忽略重复启动")
            return
        }

        val currentScope = scope
        if (currentScope == null) {
            LogFileManager.writeLog("TaskScheduler scope 未初始化")
            return
        }

        _isRunning.value = true

        val tempJob = currentScope.launch {
            while (isActive) {
                val today = LocalDate.now()

                // 今天已经处理过了，不再重复
                if (lastProcessedDate == today) {
                    LogFileManager.writeLog("今日已处理，等待下一次重置")
                    if (isActive) waitUntilNextReset()
                    continue
                }

                if (shouldSkipToday()) {
                    _tipsEvent.emit(TipsEvent.Skip)
                    ForegroundRunningService.emitNotificationText("今日休息，任务已跳过")
                } else {
                    val schedule = buildTodaySchedule()
                    if (schedule.isEmpty()) {
                        LogFileManager.writeLog("任务列表为空，停止调度")
                        return@launch
                    }

                    LogFileManager.writeLog("开始执行每日任务，共 ${schedule.size} 个")
                    executeSchedule(schedule)
                }

                lastProcessedDate = today

                // 今天结束，睡到明天
                if (isActive) waitUntilNextReset()
            }
        }
        tempJob.invokeOnCompletion {
            _isRunning.value = false
        }
        job = tempJob
    }

    /**
     * 获取当日 flag
     * */
    fun getDayFlag(): String {
        val today = LocalDate.now()
        return when {
            ChinaHolidayManager.isWorkday(today) -> "补班日"
            CustomWorkdayManager.isWeekdayRestDay(today) -> "休息日"
            ChinaHolidayManager.isHoliday(today) -> "节假日"
            else -> "工作日"
        }
    }

    /**
     * 链式任务主循环
     * for 循环保证顺序执行，每个任务经历三个阶段：
     *   阶段1 - delay(到任务时间) + 通知栏秒级倒计时
     *   阶段2 - openApplication() + select{超时|打卡} 竞态等待
     *   阶段3 - 推进到下一个任务（或全部完成 emit Completed）
     */
    private suspend fun CoroutineScope.executeSchedule(schedule: List<ScheduledTask>) {
        var executedCount = 0
        var skippedCount = 0

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

            // ====== 阶段 1：倒计时等待 ======
            val delayMs = task.actualTimeMillis - now
            _tipsEvent.emit(
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

            updateCountdownWithNotification(delayMs) { remaining ->
                val seconds = (remaining / 1000).toInt()
                // 更新通知栏
                ForegroundRunningService.emitNotificationText("${seconds.formatTime()}后执行第${task.displayIndex}个任务")
            }

            // ====== 阶段 2：打开目标 App，等待打卡或超时 ======
            val timeoutSeconds = SaveKeyValues.loadInt(
                Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
            )

            DailyTaskApplication.get().openApplication()

            // Kotlin语法糖——竞态保护：select 只取先完成的分支，另一个自动取消
            var hasCaptured = false
            var captureDeferred: CompletableDeferred<String?>? = null
            val timeoutJob = launch {
                updateCountdownWithNotification(timeoutSeconds * 1000L) { remaining ->
                    val tick = (remaining / 1000).toInt()
                    FloatingWindowController.updateTime(tick)

                    // 最后 5 秒兜底截屏（只触发一次）
                    if (tick <= 5 && !hasCaptured) {
                        val resultSource = SaveKeyValues.loadInt(
                            Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX
                        )
                        if (resultSource == 1) {
                            hasCaptured = true
                            captureDeferred = CaptureImageService.requestCaptureScreen()
                        }
                    }
                }
            }

            val clockInSuccess = select {
                // 分支 A：超时
                timeoutJob.onJoin { false }

                // 分支 B：打卡成功
                CompletableDeferred<Unit>().also { clockInDeferred = it }.onAwait { true }
            }

            timeoutJob.cancel()
            clockInDeferred = null

            // 超时路径——打卡失败，回到主页 + 兜底通知 + 继续下一个任务
            if (!clockInSuccess) {
                _returnToApp.emit(Unit)

                // 发送兜底截图给用户
                if (hasCaptured) {
                    // Deferred 内部已有 3s 超时兜底，await() 不会无限挂起
                    val imagePath = captureDeferred?.await() ?: ""
                    if (imagePath.isNotEmpty()) {
                        MessageDispatcher.sendAttachmentMessage(
                            "任务执行结果通知", "任务执行结果见附件", imagePath
                        )
                    } else {
                        MessageDispatcher.sendMessage("任务执行结果通知", "截屏失败，imagePath 为空")
                    }
                } else {
                    // 通知模式：无截图，纯文本提醒
                    MessageDispatcher.sendMessage(
                        "任务执行结果通知", "任务超时，请手动检查是否打卡成功"
                    )
                }
            }

            // ====== 阶段 3：回到主界面，处理结果 ======
            executedCount++
        }

        // ====== 全部完成 ======
        val message = when {
            executedCount + skippedCount == 0 -> "无任务可供执行"
            executedCount == 0 -> "今日所有任务均已过期，跳过（$skippedCount 个），无需执行"
            skippedCount > 0 -> "今日任务已全部执行完毕（执行 $executedCount 个，跳过 $skippedCount 个）"
            else -> "今日任务已全部执行完毕"
        }
        LogFileManager.writeLog(message)
        ForegroundRunningService.emitNotificationText(message)
    }

    /**
     * 调试用：非 null 时跳过真实计算，直接使用指定秒数
     * 生产环境保持 null
     */
    @Volatile
    var debugWaitSeconds: Long? = null

    /**
     * 等待到下一个每日重置时间
     */
    private suspend fun waitUntilNextReset() {
        val resetHour = SaveKeyValues.loadInt(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        )

        val waitSeconds = debugWaitSeconds ?: calculateSecondsUntilReset(resetHour)
        if (waitSeconds <= 0L) return  // 防御性代码：防止自旋

        LogFileManager.writeLog("等待 ${waitSeconds}s 后进入下一个任务周期")

        // 只发一次静态通知，不每秒刷新
        _tipsEvent.emit(TipsEvent.Completed)
        ForegroundRunningService.emitNotificationText("今日任务已执行完毕，等待下次任务")

        delay(waitSeconds * 1000)
    }

    /**
     * 打卡成功通知
     * 调用链：NotificationMonitorService.onNotificationPosted()
     *       → MainActivity.onClockInSuccess()
     *       → TaskScheduler.notifyClockIn()
     * 效果：完成 clockInDeferred，select{} 走分支 B，推进到下一个任务
     */
    fun notifyClockIn() {
        clockInDeferred?.complete(Unit)
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
        ForegroundRunningService.emitNotificationText("为保证程序正常运行，请勿移除此通知")
    }

    /**
     * 因外部错误请求停止（目标 App 未安装、启动失败等）
     * 由 Context.openApplication() 在无法打开目标 App 时调用
     *
     * 与 stopTask() 的区别：
     *   stopTask()     — 用户主动点击"停止"，发消息通知
     *   requestStopDueToError() — 系统错误停止，不发消息通知，只重置调度器
     */
    fun requestStopDueToError(reason: String) {
        LogFileManager.writeLog("因错误请求停止：$reason")
        job?.cancel()
        job = null
        _isRunning.value = false
    }

    /**
     * 自校准倒计时 tick，支持 UI 回调。
     * 使用 elapsedRealtime 确保休眠唤醒后剩余时间准确。
     */
    private suspend fun CoroutineScope.updateCountdownWithNotification(
        totalMs: Long, onTick: (remainingMs: Long) -> Unit
    ) {
        val target = SystemClock.elapsedRealtime() + totalMs
        while (isActive) {
            val remaining = target - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            onTick(remaining)
            val step = minOf(1000L, remaining).coerceAtLeast(1)
            delay(step)
        }
    }

    private fun shouldSkipToday(): Boolean {
        val skipEnabled = SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)
        if (!skipEnabled) return false

        val today = LocalDate.now()

        // 调休补班日（覆盖一切，必须执行）
        if (ChinaHolidayManager.isWorkday(today)) {
            LogFileManager.writeLog("今日为调休补班日，正常执行任务")
            return false
        }

        // 法定节假日 → 跳过
        if (ChinaHolidayManager.isHoliday(today)) {
            LogFileManager.writeLog("今日为法定节假日，跳过任务")
            return true
        }

        // 一周休息日（默认周六日双休，用户可修改）→ 跳过
        if (CustomWorkdayManager.isWeekdayRestDay(today)) {
            LogFileManager.writeLog("今日为休息日，跳过任务")
            return true
        }

        // 其余情况 → 正常执行
        return false
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

    /**
     * 计算距离下一次重置还有多少秒
     */
    private fun calculateSecondsUntilReset(resetHour: Int): Long {
        val now = Calendar.getInstance()
        val target = now.clone() as Calendar
        target.set(Calendar.HOUR_OF_DAY, resetHour)
        target.set(Calendar.MINUTE, 0)
        target.set(Calendar.SECOND, 0)
        target.set(Calendar.MILLISECOND, 0)

        if (now.timeInMillis >= target.timeInMillis) {
            target.add(Calendar.DATE, 1)
        }

        return ((target.timeInMillis - now.timeInMillis) / 1000).coerceAtLeast(1)
    }

    private data class ScheduledTask(
        val task: DailyTaskBean,
        val displayIndex: Int,
        val plannedTime: String,
        val actualTime: String,
        val actualTimeMillis: Long
    )
}
