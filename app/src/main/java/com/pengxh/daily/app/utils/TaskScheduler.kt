package com.pengxh.daily.app.utils

import android.content.Context
import android.content.Intent
import com.pengxh.daily.app.extensions.resolveExecutionTime
import com.pengxh.daily.app.service.CountDownTimerService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * 任务调度器
 *
 * 每次 startTask() 时：
 *   1. 判断日类型（法定假日 / 调休补班 / 普通周末）
 *   2. 构建当日计划表（一次性计算所有任务的实际执行时间，当天不变）
 *   3. 按计划表链式调度（找下一个未来任务 → 倒计时等待 → 执行 → 找下一个）
 */
class TaskScheduler(
    private val context: Context,
    private val listener: TaskStateListener
) {
    companion object {
        private const val INVALID_TASK_INDEX = -1
    }

    /**
     * 调度器内部状态
     */
    private enum class State {
        /**
         * 空闲，未启动
         * */
        IDLE,

        /**
         * 正在链式执行中
         * */
        EXECUTING,

        /**
         * 当日已跳过（节假日/周末）
         * */
        SKIPPED,

        /**
         * 当日全部任务已执行完毕
         * */
        COMPLETED
    }

    /**
     * 当日任务计划项
     *
     * 在 startTask() 时一次性计算所有任务，当天后续不再查询数据库或重新计算偏移
     */
    private data class ScheduledTask(
        val task: DailyTaskBean,
        val displayIndex: Int,         // 显示编号（1-based）
        val plannedTime: String,       // 用户设定的计划时间 "HH:mm:ss"
        val actualTime: String,        // 计划时间 + 随机偏移 = 实际时间 "HH:mm:ss"
        val actualTimeMillis: Long     // 实际时间的毫秒时间戳（当天 00:00:00 + 时分秒偏移）
    )

    interface TaskStateListener {
        fun onTaskStarted()
        fun onTaskStopped()
        fun onTaskSkipped(message: String)
        fun onTaskCompleted()
        fun onTaskExecuting(taskIndex: Int, task: DailyTaskBean, realTime: String)
        fun onTaskExecutionError(message: String)
    }

    private var currentState: State = State.IDLE
    private var todaySchedule: List<ScheduledTask> = emptyList()
    private var executedCount = 0

    fun isTaskStarted() = currentState == State.EXECUTING || currentState == State.SKIPPED

    /**
     * 启动每日任务调度
     */
    fun startTask() {
        if (currentState == State.EXECUTING || currentState == State.SKIPPED) {
            LogFileManager.writeLog("任务已在执行中，忽略重复启动")
            return
        }

        // Step 1: 判断日类型（法定节假日 / 调休补班 / 普通周末）
        if (shouldSkipToday()) {
            currentState = State.SKIPPED
            listener.onTaskSkipped("今日为周末，跳过任务")
            return
        }

        // Step 2: 一次性构建当日计划表
        todaySchedule = buildTodaySchedule()
        if (todaySchedule.isEmpty()) {
            listener.onTaskExecutionError("任务启动失败，请先添加任务时间点")
            return
        }

        // Step 3: 初始化计数，开始链式调度
        executedCount = 0
        currentState = State.EXECUTING
        LogFileManager.writeLog("开始执行每日任务")
        listener.onTaskStarted()

        scheduleNextTask()
    }

    /**
     * 停止任务调度
     */
    fun stopTask() {
        if (currentState != State.EXECUTING && currentState != State.COMPLETED && currentState != State.SKIPPED) {
            LogFileManager.writeLog("任务未运行，无需停止")
            return
        }

        LogFileManager.writeLog("停止执行每日任务")
        currentState = State.IDLE
        cancelCountdownService()
        listener.onTaskStopped()
    }

    /**
     * 标记当前任务已完成，调度下一个
     * 由 MainActivity 在收到打卡成功广播后调用
     */
    fun executeNextTask() {
        if (currentState != State.EXECUTING) {
            LogFileManager.writeLog("任务未运行，忽略执行下一个任务")
            return
        }
        LogFileManager.writeLog("执行下一个任务")
        executedCount++
        scheduleNextTask()
    }

    /**
     * 判断今天是否需要跳过任务
     *
     * 跳过条件（任一满足即跳过）：
     *   1. 中国法定节假日（含调休放假，如国庆假期中的工作日）
     *   2. 普通周六、周日（但被标记为调休补班的周末除外）
     *
     * 不跳过的情况：
     *   1. 普通周一 ~ 周五
     *   2. 调休补班日（虽然是周末，但需要上班）
     */
    private fun shouldSkipToday(): Boolean {
        val skipEnabled = SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)
        if (!skipEnabled) return false

        val today = LocalDate.now()

        // 1. 法定节假日 → 跳过
        if (ChinaHolidayManager.isHoliday(today)) {
            LogFileManager.writeLog("今日为法定节假日，跳过任务")
            return true
        }

        // 2. 调休补班日（如国庆前的周六要上班）→ 不跳过
        if (ChinaHolidayManager.isWorkday(today)) {
            LogFileManager.writeLog("今日为调休补班日，正常执行任务")
            return false
        }

        // 3. 普通周六/周日 → 跳过
        val dayOfWeek = today.dayOfWeek
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            LogFileManager.writeLog("今日为周末，跳过任务")
            return true
        }

        return false
    }

    /**
     * 构建当日的完整任务计划表
     *
     * - 从数据库加载所有任务（唯一一次 DB 查询）
     * - 为每个任务计算 计划时间 + 随机偏移 = 实际时间
     * - 按实际时间升序排列，保证链式顺序正确
     */
    private fun buildTodaySchedule(): List<ScheduledTask> {
        val allTasks = DatabaseWrapper.loadAllTask()
        if (allTasks.isEmpty()) return emptyList()

        // 当天 00:00:00 的时间戳，作为时间偏移的基准
        val baseMillis = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        return allTasks.mapIndexed { index, task ->
            // 计划时间 + 随机偏移 = 实际执行时间（一天只算这一次）
            val actualTime = task.resolveExecutionTime()
            val timeParts = actualTime.split(":").map { it.toInt() }
            val actualMillis = baseMillis +
                    timeParts[0] * 3_600_000L +
                    timeParts[1] * 60_000L +
                    timeParts[2] * 1_000L
            ScheduledTask(
                task = task,
                displayIndex = index + 1,
                plannedTime = task.time,
                actualTime = actualTime,
                actualTimeMillis = actualMillis
            )
        }.sortedBy { it.actualTimeMillis }
    }

    /**
     * 从计划表中找到下一个尚未执行且时间在未来的任务，启动倒计时
     *
     * 已执行过的任务（executedCount 之前的）不再考虑；
     * 已过时间的任务也会被跳过，直到找到第一个 future 任务
     */
    private fun scheduleNextTask() {
        val now = System.currentTimeMillis()

        val nextTask = todaySchedule
            .drop(executedCount)               // 跳过已执行的
            .firstOrNull { it.actualTimeMillis > now }  // 找第一个在未来的

        if (nextTask == null) {
            handleAllTasksCompleted()
            return
        }

        // 距离执行时间还有多少秒（至少 1 秒，CountDownTimerService 要求 > 0）
        val delaySeconds = ((nextTask.actualTimeMillis - now) / 1000)
            .toInt()
            .coerceAtLeast(1)

        LogFileManager.writeLog(
            "调度第 ${nextTask.displayIndex} 个任务，" +
                    "计划时间=${nextTask.plannedTime}，" +
                    "实际时间=${nextTask.actualTime}，" +
                    "延迟=${delaySeconds}s"
        )

        listener.onTaskExecuting(nextTask.displayIndex, nextTask.task, nextTask.actualTime)
        startCountdownService(nextTask.displayIndex, delaySeconds)
    }

    private fun handleAllTasksCompleted() {
        LogFileManager.writeLog("今日任务已全部执行完毕")
        currentState = State.COMPLETED
        listener.onTaskCompleted()
        notifyServiceTaskCompleted()
    }

    // ============================================================
    // 倒计时服务通信
    // ============================================================
    private fun sendCommandToService(action: String, taskIndex: Int = -1, seconds: Int = 0) {
        Intent(context, CountDownTimerService::class.java).apply {
            this.action = action
            if (taskIndex != INVALID_TASK_INDEX) {
                putExtra(CountDownTimerService.EXTRA_TASK_INDEX, taskIndex)
            }
            if (seconds > 0) {
                putExtra(CountDownTimerService.EXTRA_SECONDS, seconds)
            }
            // 使用 startService（非 startForegroundService），Service 已在后台运行
            context.startService(this)
        }
    }

    private fun notifyServiceTaskCompleted() {
        sendCommandToService(CountDownTimerService.ACTION_COMPLETED_DAILY_TASK)
    }

    private fun cancelCountdownService() {
        sendCommandToService(CountDownTimerService.ACTION_CANCEL_COUNTDOWN)
    }

    private fun startCountdownService(taskIndex: Int, seconds: Int) {
        sendCommandToService(CountDownTimerService.ACTION_START_COUNTDOWN, taskIndex, seconds)
    }
}
