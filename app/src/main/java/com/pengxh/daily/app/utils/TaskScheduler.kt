package com.pengxh.daily.app.utils

import android.content.Context
import android.os.SystemClock
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.extensions.resolveExecutionTime
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.greenrobot.eventbus.EventBus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * 任务调度器（协程版）
 *
 * 完整时序：
 *   ┌─────────────────────────────────────────────────────────┐
 *   │ ForegroundRunningService.onCreate()                     │
 *   │   → attach(serviceScope)        注入协程作用域             │
 *   └─────────────────────────────────────────────────────────┘
 *                              ↓
 *   ┌─────────────────────────────────────────────────────────┐
 *   │ MainActivity 用户点击"启动" / Alarm 触发 / 兜底检查          │
 *   │   → startTask(ctx)                                      │
 *   │     ├─ isRunning? → 防重复                               │
 *   │     ├─ scope == null? → 未初始化                         │
 *   │     ├─ shouldSkipToday()? → Skipped (周末/节假日)         │
 *   │     ├─ buildTodaySchedule() → 空? → Idle                │
 *   │     └─ launch { executeSchedule() }                     │
 *   └─────────────────────────────────────────────────────────┘
 *                              ↓
 *   ┌─────────────────────────────────────────────────────────┐
 *   │ executeSchedule()  链式逐任务执行                          │
 *   │   for task in schedule:                                 │
 *   │     ├─ 过期? → continue                                  │
 *   │     ├─ emit Executing(taskIndex, task, actualTime, ...) │
 *   │     ├─ 阶段1: countdownWithUI(delayMs)                   │
 *   │     │         → EventBus → 通知栏秒级倒计时                │
 *   │     ├─ 阶段2: openApplication()                          │
 *   │     │         select { 超时 | 打卡成功 }                   │
 *   │     │          分支A: timeoutJob.onJoin → false          │
 *   │     │          分支B: clockInDeferred.onAwait → true     │
 *   │     └─ 阶段3: 推进下一个任务                                │
 *   │   → emit Completed                                      │
 *   └─────────────────────────────────────────────────────────┘
 *                              ↓
 *   ┌─────────────────────────────────────────────────────────┐
 *   │ 外部触发：                                                │
 *   │   notifyClockIn()     ← MainActivity.onClockInSuccess() │
 *   │                         → complete(clockInDeferred)     │
 *   │                                                         │
 *   │   遥控"打卡"独立到                                         │
 *   │   NotificationMonitorService                            │
 *   │   不触发任何 TaskScheduler 逻辑                            │
 *   └─────────────────────────────────────────────────────────┘
 *                              ↓
 *   ┌─────────────────────────────────────────────────────────┐
 *   │ stopTask()  用户点击"停止" / 遥控"终止任务"                  │
 *   │   → job?.cancel() + emit Idle                           │
 *   └─────────────────────────────────────────────────────────┘
 */
object TaskScheduler {
    /**
     * 对外暴露的调度状态，Activity 通过 collect 驱动 UI
     * */
    private val _state = MutableStateFlow<SchedulerState>(SchedulerState.Idle)
    val state: StateFlow<SchedulerState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /**
     * 打卡信号：外部 notifyClockIn() 触发，解除 select{} 阻塞
     * */
    private var clockInDeferred: CompletableDeferred<Unit>? = null

    /**
     * 由 ForegroundRunningService 调用，注入协程作用域
     */
    fun attach(serviceScope: CoroutineScope) {
        scope?.cancel()
        scope = serviceScope
    }

    fun isRunning(): Boolean {
        val s = _state.value
        return s is SchedulerState.Executing || s is SchedulerState.Skipped
    }

    /**
     * 启动每日任务调度
     * 时序：防重复 → 检查协程作用域 → 判断周末/节假日 → 构建排程 → 启动核心循环
     */
    fun startTask(context: Context) {
        val currentState = _state.value
        if (currentState is SchedulerState.Executing || currentState is SchedulerState.Skipped) {
            LogFileManager.writeLog("任务已在执行中，忽略重复启动")
            return
        }

        val currentScope = scope
        if (currentScope == null) {
            LogFileManager.writeLog("TaskScheduler scope 未初始化")
            return
        }

        if (shouldSkipToday()) {
            _state.update { SchedulerState.Skipped }
            EventBus.getDefault().post(
                ApplicationEvent.UpdateNotification("今日休息，任务已跳过")
            )
            return
        }

        val schedule = buildTodaySchedule()
        if (schedule.isEmpty()) {
            _state.update { SchedulerState.Idle }
            return
        }

        LogFileManager.writeLog("开始执行每日任务，共 ${schedule.size} 个")
        job = currentScope.launch {
            executeSchedule(context, schedule)
        }
    }

    /**
     * 链式任务主循环
     * for 循环保证顺序执行，每个任务经历三个阶段：
     *   阶段1 - delay(到任务时间) + 通知栏秒级倒计时
     *   阶段2 - openApplication() + select{超时|打卡} 竞态等待
     *   阶段3 - 推进到下一个任务（或全部完成 emit Completed）
     */
    private suspend fun CoroutineScope.executeSchedule(
        context: Context,
        schedule: List<ScheduledTask>
    ) {
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
            _state.update {
                SchedulerState.Executing(
                    task.displayIndex, task.task, task.actualTime, schedule.size
                )
            }

            LogFileManager.writeLog(
                "调度第 ${task.displayIndex} 个任务，" +
                        "计划时间=${task.plannedTime}，" +
                        "实际时间=${task.actualTime}，" +
                        "延迟=${delayMs / 1000}s"
            )

            updateCountdownWithNotification(delayMs) { remaining ->
                val seconds = (remaining / 1000).toInt()
                // 更新通知栏
                EventBus.getDefault().post(
                    ApplicationEvent.UpdateNotification(
                        "${seconds.formatTime()}后执行第${task.displayIndex}个任务"
                    )
                )
            }

            // ====== 阶段 2：打开目标 App，等待打卡或超时 ======
            val timeoutSeconds = SaveKeyValues.loadInt(
                Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
            )

            context.openApplication()

            // Kotlin语法糖——竞态保护：select 只取先完成的分支，另一个自动取消
            var hasCaptured = false
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
                            EventBus.getDefault().post(ApplicationEvent.CaptureScreen)
                        }
                    }
                }
            }

            select {
                // 分支 A：超时
                timeoutJob.onJoin { false }

                // 分支 B：打卡成功
                CompletableDeferred<Unit>().also { clockInDeferred = it }.onAwait { true }
            }

            timeoutJob.cancel()
            clockInDeferred = null

            // ====== 阶段 3：回到主界面，处理结果 ======
            executedCount++
        }

        // ====== 全部完成 ======
        val message = when {
            executedCount + skippedCount == 0 -> "无任务可供执行"
            executedCount == 0 -> "今日所有任务均已过期跳过（$skippedCount 个），无需执行"
            skippedCount > 0 -> "今日任务已全部执行完毕（执行 $executedCount 个，跳过 $skippedCount 个）"
            else -> "今日任务已全部执行完毕"
        }
        LogFileManager.writeLog(message)
        _state.update { SchedulerState.Completed }
        EventBus.getDefault().post(
            ApplicationEvent.UpdateNotification(message)
        )
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
        val currentState = _state.value
        if (currentState !is SchedulerState.Executing
            && currentState !is SchedulerState.Completed
            && currentState !is SchedulerState.Skipped
        ) {
            LogFileManager.writeLog("任务未运行，无需停止")
            return
        }

        LogFileManager.writeLog("停止执行每日任务")
        job?.cancel()
        job = null
        _state.update { SchedulerState.Idle }
        EventBus.getDefault().post(
            ApplicationEvent.UpdateNotification("为保证程序正常运行，请勿移除此通知")
        )
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

        // 法定节假日
        if (ChinaHolidayManager.isHoliday(today)) {
            LogFileManager.writeLog("今日为法定节假日，跳过任务")
            return true
        }

        // 调休补班日（例外：周末但要上班）
        if (ChinaHolidayManager.isWorkday(today)) {
            LogFileManager.writeLog("今日为调休补班日，正常执行任务")
            return false
        }

        // 普通周末
        val dayOfWeek = today.dayOfWeek
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            LogFileManager.writeLog("今日为周末，跳过任务")
            return true
        }

        return false
    }

    /**
     * 从数据库加载所有任务，计算出当日实际执行时间，按时间排序
     * */
    private fun buildTodaySchedule(): List<ScheduledTask> {
        val allTasks = DatabaseWrapper.loadAllTask()
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
        }.sortedBy { it.third }
            .mapIndexed { index, (task, actualTime, actualMillis) ->
                ScheduledTask(task, index + 1, task.time, actualTime, actualMillis)
            }
    }

    private data class ScheduledTask(
        val task: DailyTaskBean,
        val displayIndex: Int,
        val plannedTime: String,
        val actualTime: String,
        val actualTimeMillis: Long
    )
}