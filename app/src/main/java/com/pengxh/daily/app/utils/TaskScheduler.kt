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
 * 核心思路：
 * - 倒计时等待 → delay() + tick 循环（替代 CountDownTimerService）
 * - 等待打卡/超时 → select {} 天然竞态保护（替代 TimeoutTimerManager）
 * - UI 更新 → StateFlow emit，Activity collect（替代 TaskStateListener 回调接口）
 *
 * 调度流程：
 *   for each task in schedule:
 *     delay(到任务时间)
 *     openApplication()
 *     select { timeout() vs clockIn() }  → 谁先到用谁，另一个自动取消
 *     推进到下一个任务
 *
 * TODO 功能还有缺陷，还未能自洽，待修改
 * 调度器 -> 服务 -> 主活动
 * [TaskScheduler] -> [com.pengxh.daily.app.service.ForegroundRunningService] -> [com.pengxh.daily.app.ui.MainActivity]
 */
object TaskScheduler {

    // ---- 对外状态 ----
    private val _state = MutableStateFlow<SchedulerState>(SchedulerState.Idle)
    val state: StateFlow<SchedulerState> = _state.asStateFlow()

    // ---- 内部状态 ----
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var clockInDeferred: CompletableDeferred<Unit>? = null
    private var forceAdvanceDeferred: CompletableDeferred<Unit>? = null

    /**
     * 由 ForegroundRunningService 调用，注入协程作用域
     */
    fun attach(serviceScope: CoroutineScope) {
        scope?.cancel()
        scope = serviceScope
    }

    /**
     * 检测任务是否正在运行
     */
    fun isRunning(): Boolean {
        val s = _state.value
        return s is SchedulerState.Executing || s is SchedulerState.Skipped
    }

    // ============================================================
    // 对外控制方法
    // ============================================================

    fun startTask(ctx: Context) {
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
            return
        }

        val schedule = buildTodaySchedule()
        if (schedule.isEmpty()) {
            _state.update { SchedulerState.Idle }
            // 通过 emit 错误态，Activity 自行处理
            return
        }

        LogFileManager.writeLog("开始执行每日任务，共 ${schedule.size} 个")

        job = currentScope.launch {
            executeSchedule(ctx.applicationContext, schedule)
        }
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
    }

    /** 打卡成功通知（由 NotificationMonitorService → MainActivity 调用） */
    fun notifyClockIn() {
        clockInDeferred?.complete(Unit)
    }

    /**
     * 遥控指令"打卡"触发：打开目标 App → 倒计时 → 强制推进链式任务
     * 用于遥控场景：用户发送关键字 → 打开 App → 等待超时 → 推进链式任务
     */
    fun countdownAndAdvance() {
        scope?.launch {
            val timeoutSeconds = SaveKeyValues.loadInt(
                Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
            )
            countdownWithUI(timeoutSeconds * 1000L) { remaining ->
                FloatingWindowController.updateTime((remaining / 1000).toInt())
            }
            // 超时：强制推进当前链式任务（等效于超时分支）
            forceAdvanceDeferred?.complete(Unit)
        }
    }

    // ============================================================
    // 核心调度循环
    // ============================================================

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
                    taskIndex = task.displayIndex,
                    task = task.task,
                    actualTime = task.actualTime,
                    totalTasks = schedule.size
                )
            }

            LogFileManager.writeLog(
                "调度第 ${task.displayIndex} 个任务，" +
                        "计划时间=${task.plannedTime}，" +
                        "实际时间=${task.actualTime}，" +
                        "延迟=${delayMs / 1000}s"
            )

            countdownWithUI(delayMs) { remaining ->
                val seconds = (remaining / 1000).toInt()
                // 更新通知栏
                EventBus.getDefault().post(
                    ApplicationEvent.UpdateCountdownText(
                        "${seconds.formatTime()}后执行第${task.displayIndex}个任务"
                    )
                )
            }

            // ====== 阶段 2：打开目标 App，等待打卡或超时 ======
            val timeoutSeconds = SaveKeyValues.loadInt(
                Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
            )

            context.openApplication()

            // 竞态保护：select 只取先完成的分支，另一个自动取消
            var hasCaptured = false
            // 启动超时倒计时 coroutine（在 select 外部，因为 select {} 的 receiver 是 SelectBuilder）
            val timeoutJob = launch {
                countdownWithUI(timeoutSeconds * 1000L) { remaining ->
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
                // 分支 A：超时 → 未打卡成功
                timeoutJob.onJoin { false }

                // 分支 B：打卡成功
                CompletableDeferred<Unit>().also { clockInDeferred = it }.onAwait { true }

                // 分支 C：遥控指令"打卡"强制推进（countdownAndAdvance 中倒计时结束后触发）
                CompletableDeferred<Unit>().also { forceAdvanceDeferred = it }.onAwait { false }
            }
            // 如果 select 选择了分支 B/C，则取消仍在进行的倒计时
            timeoutJob.cancel()

            clockInDeferred = null
            forceAdvanceDeferred = null

            executedCount++

            // ====== 阶段 3：回到主界面，处理结果 ======
            // 注意：不在这里做 backToMainActivity，交给 MainActivity 通过状态变化处理
            // TaskScheduler 只负责调度，导航逻辑由 Activity 自己处理
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
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /**
     * 自校准倒计时 tick，支持 UI 回调。
     * 使用 elapsedRealtime 确保休眠唤醒后剩余时间准确。
     */
    private suspend fun CoroutineScope.countdownWithUI(
        totalMs: Long,
        onTick: (remainingMs: Long) -> Unit
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

        if (ChinaHolidayManager.isHoliday(today)) {
            LogFileManager.writeLog("今日为法定节假日，跳过任务")
            return true
        }

        if (ChinaHolidayManager.isWorkday(today)) {
            LogFileManager.writeLog("今日为调休补班日，正常执行任务")
            return false
        }

        val dayOfWeek = today.dayOfWeek
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            LogFileManager.writeLog("今日为周末，跳过任务")
            return true
        }

        return false
    }

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

    // ============================================================
    // 数据类
    // ============================================================

    private data class ScheduledTask(
        val task: DailyTaskBean,
        val displayIndex: Int,
        val plannedTime: String,
        val actualTime: String,
        val actualTimeMillis: Long
    )
}

/**
 * 调度器对外状态
 */
sealed class SchedulerState {
    /** 空闲 */
    data object Idle : SchedulerState()

    /** 节假日跳过 */
    data object Skipped : SchedulerState()

    /** 正在执行某个任务 */
    data class Executing(
        val taskIndex: Int,
        val task: DailyTaskBean,
        val actualTime: String,
        val totalTasks: Int
    ) : SchedulerState()

    /** 当日所有任务已完成 */
    data object Completed : SchedulerState()
}
