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
 *   2. 构建当日计划表，放入 FIFO 队列（按实际执行时间升序）
 *   3. 从队列头部依次取任务 → 倒计时等待 → 执行 → 出队 → 取下一个
 *
 * 线程安全：所有修改状态的方法均使用 @Synchronized 保护。
 * 打卡回调（NotificationMonitorService binder 线程）与超时回调（主线程）可能并发到达。
 */
class TaskScheduler(private val context: Context, private val listener: TaskStateListener) {

    private enum class State { IDLE, EXECUTING, SKIPPED, COMPLETED }

    private data class ScheduledTask(
        val task: DailyTaskBean,
        val displayIndex: Int,         // 显示编号（1-based）
        val plannedTime: String,       // 用户设定的计划时间 "HH:mm:ss"
        val actualTime: String,        // 计划时间 + 随机偏移 = 实际时间 "HH:mm:ss"
        val actualTimeMillis: Long     // 实际时间的毫秒时间戳
    )

    interface TaskStateListener {
        fun onTaskStarted()
        fun onTaskSkipped(message: String)
        fun onTaskCompleted()
        fun onTaskExecuting(taskIndex: Int, task: DailyTaskBean, realTime: String)
        fun onTaskExecutionError(message: String)
        fun onTaskStopped()
    }

    private var currentState: State = State.IDLE

    /**
     * 待执行任务队列（FIFO，按实际执行时间升序排列）
     * */
    private val pendingQueue: ArrayDeque<ScheduledTask> = ArrayDeque()

    /**
     * 已执行的任务计数
     * */
    private var executedCount = 0

    /** 已过期跳过的任务计数 */
    private var skippedCount = 0

    /**
     * 当前等待中的任务 displayIndex
     * -1 表示当前没有正在等待的任务
     */
    private var awaitingIndex = -1

    /**
     * 当前等待中的任务是否已被解决（已打卡或已超时）。
     * 防止同一任务的重复通知 / 打卡 + 超时竞态导致两次推进。
     */
    private var isResolved = false

    fun isTaskStarted() = currentState == State.EXECUTING || currentState == State.SKIPPED


    /**
     * 启动每日任务调度
     */
    @Synchronized
    fun startTask() {
        if (currentState == State.EXECUTING || currentState == State.SKIPPED) {
            LogFileManager.writeLog("任务已在执行中，忽略重复启动")
            return
        }

        if (shouldSkipToday()) {
            currentState = State.SKIPPED
            listener.onTaskSkipped("今日为周末，跳过任务")
            return
        }

        val schedule = buildTodaySchedule()
        if (schedule.isEmpty()) {
            currentState = State.IDLE
            listener.onTaskExecutionError("任务启动失败，请先添加任务时间点")
            return
        }

        // 初始化队列与计数
        pendingQueue.clear()
        pendingQueue.addAll(schedule)
        executedCount = 0
        skippedCount = 0
        awaitingIndex = -1
        isResolved = false
        currentState = State.EXECUTING

        LogFileManager.writeLog("开始执行每日任务，共 ${pendingQueue.size} 个")
        listener.onTaskStarted()
        scheduleNextTask()
    }

    /**
     * 标记当前任务已完成（打卡成功 / 超时兜底），推进到下一个
     * 由 MainActivity 在收到打卡成功通知或超时回调时调用
     */
    @Synchronized
    fun executeNextTask() {
        if (currentState != State.EXECUTING) {
            LogFileManager.writeLog(
                "任务未运行，忽略执行下一个任务（当前状态=$currentState）"
            )
            return
        }
        if (isResolved) {
            LogFileManager.writeLog(
                "当前等待的任务（第 $awaitingIndex 个）已被解决，忽略重复推进"
            )
            return
        }
        isResolved = true
        LogFileManager.writeLog("任务第 $awaitingIndex 个已解决，推进到下一个")

        // 消费队头（当前任务）
        pendingQueue.removeFirst()
        executedCount++
        awaitingIndex = -1
        scheduleNextTask()
    }

    /**
     * 停止任务调度
     */
    @Synchronized
    fun stopTask() {
        if (currentState != State.EXECUTING && currentState != State.COMPLETED && currentState != State.SKIPPED) {
            LogFileManager.writeLog("任务未运行，无需停止")
            return
        }

        LogFileManager.writeLog("停止执行每日任务")
        if (currentState == State.EXECUTING || currentState == State.SKIPPED) {
            cancelCountdownService()
        }
        currentState = State.IDLE
        listener.onTaskStopped()
    }

    /**
     * 从队列头部取任务：过期则跳过（出队），找到第一个未来任务则启动倒计时。
     * 队列为空时结束当日调度。
     */
    @Synchronized
    private fun scheduleNextTask() {
        while (pendingQueue.isNotEmpty()) {
            val head = pendingQueue.first()
            val now = System.currentTimeMillis()

            if (head.actualTimeMillis <= now) {
                // 任务时间已过，跳过
                pendingQueue.removeFirst()
                skippedCount++
                LogFileManager.writeLog(
                    "第 ${head.displayIndex} 个任务已过期（计划=${head.plannedTime}，" +
                            "实际=${head.actualTime}），跳过"
                )
                continue
            }

            // 找到第一个还在未来的任务
            val delaySeconds = ((head.actualTimeMillis - now) / 1000).toInt().coerceAtLeast(1)

            LogFileManager.writeLog(
                "调度第 ${head.displayIndex} 个任务，" +
                        "计划时间=${head.plannedTime}，" +
                        "实际时间=${head.actualTime}，" +
                        "延迟=${delaySeconds}s"
            )

            listener.onTaskExecuting(head.displayIndex, head.task, head.actualTime)
            awaitingIndex = head.displayIndex
            isResolved = false
            startCountdownService(head.displayIndex, delaySeconds)
            return
        }

        // 队列为空
        handleAllTasksCompleted()
    }

    private fun handleAllTasksCompleted() {
        val message = when {
            executedCount + skippedCount == 0 -> "无任务可供执行"
            executedCount == 0 -> "今日所有任务均已过期跳过（$skippedCount 个），无需执行"
            skippedCount > 0 -> "今日任务已全部执行完毕（执行 $executedCount 个，跳过 $skippedCount 个）"
            else -> "今日任务已全部执行完毕"
        }
        LogFileManager.writeLog(message)
        currentState = State.COMPLETED
        listener.onTaskCompleted()
        notifyServiceTaskCompleted()
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

    /**
     * 构建当日的完整任务计划表
     *
     * - 从数据库加载所有任务（唯一一次 DB 查询）
     * - 为每个任务计算 计划时间 + 随机偏移 = 实际时间
     * - 按实际时间升序排列
     */
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
    // 倒计时服务通信
    // ============================================================

    private fun notifyServiceTaskCompleted() {
        sendCommandToService(CountDownTimerService.ACTION_COMPLETED_DAILY_TASK)
    }

    private fun cancelCountdownService() {
        sendCommandToService(CountDownTimerService.ACTION_CANCEL_COUNTDOWN)
    }

    private fun startCountdownService(taskIndex: Int, seconds: Int) {
        sendCommandToService(CountDownTimerService.ACTION_START_COUNTDOWN, taskIndex, seconds)
    }

    private fun sendCommandToService(action: String, taskIndex: Int = -1, seconds: Int = 0) {
        Intent(context, CountDownTimerService::class.java).apply {
            this.action = action
            if (taskIndex != -1) {
                putExtra(CountDownTimerService.EXTRA_TASK_INDEX, taskIndex)
            }
            if (seconds > 0) {
                putExtra(CountDownTimerService.EXTRA_SECONDS, seconds)
            }
            context.startService(this)
        }
    }
}
