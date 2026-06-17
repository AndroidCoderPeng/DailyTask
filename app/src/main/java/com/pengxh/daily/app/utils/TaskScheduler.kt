package com.pengxh.daily.app.utils

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.pengxh.daily.app.extensions.diffCurrent
import com.pengxh.daily.app.extensions.getTaskIndex
import com.pengxh.daily.app.service.CountDownTimerService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.kt.lite.utils.SaveKeyValues

/**
 * 任务调度器
 *
 * 职责：
 * 1. 管理任务启动/停止状态
 * 2. 执行每日任务调度逻辑
 * 3. 协调倒计时服务和UI更新
 *
 * @param context
 * @param listener 任务状态回调
 */
class TaskScheduler(
    private val context: Context, private val listener: TaskStateListener
) {
    companion object {
        private const val INVALID_TASK_INDEX = -1
        private const val NO_SECONDS_DELAY = 0
    }

    interface TaskStateListener {
        fun onTaskStarted()
        fun onTaskStopped()
        fun onTaskCompleted()
        fun onTaskExecuting(taskIndex: Int, task: DailyTaskBean, realTime: String)
        fun onTaskExecutionError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isTaskStarted = false

    fun isTaskStarted(): Boolean = isTaskStarted

    /**
     * 启动任务
     */
    fun startTask() {
        if (shouldSkipDueToHoliday()) {
            handleHolidaySkip()
            return
        }

        executeStartTask()
    }

    /**
     * 停止任务
     */
    fun stopTask() {
        if (!isTaskStarted) {
            LogFileManager.writeLog("任务未运行，无需停止")
            return
        }

        LogFileManager.writeLog("停止执行每日任务")
        updateTaskState(false)
        cancelScheduledTasks()
        cancelCountdownService()
        listener.onTaskStopped()
    }

    /**
     * 取消超时定时器并执行下一个任务
     * 此方法由外部调用，在收到打卡成功广播时
     */
    fun executeNextTask() {
        if (!isTaskStarted) {
            LogFileManager.writeLog("任务未运行，忽略执行下一个任务")
            return
        }
        LogFileManager.writeLog("执行下一个任务")
        rescheduleNextTask()
    }

    fun destroy() {
        mainHandler.removeCallbacks(dailyTaskRunnable)
    }

    private fun shouldSkipDueToHoliday(): Boolean {
        val skipHolidayEnabled = SaveKeyValues.getValue(
            Constant.SKIP_CHINA_HOLIDAY_KEY,
            false
        ) as Boolean

        if (!skipHolidayEnabled) {
            return false
        }

        val dayInfo = ChinaHolidayCalendar.evaluateToday()
        return dayInfo.shouldSkip
    }

    private fun handleHolidaySkip() {
        val dayInfo = ChinaHolidayCalendar.evaluateToday()
        LogFileManager.writeLog("今日为节假日 ${dayInfo.date}，跳过任务执行")

        if (!dayInfo.hasOfficialAdjustment) {
            LogFileManager.writeLog("未配置中国节假日调休表，任务按正常工作日执行")
        }

        listener.onTaskCompleted()
        notifyServiceTaskCompleted()
    }

    // ============================================================
    // 私有实现 - 任务启动核心逻辑
    // ============================================================

    private fun executeStartTask() {
        if (isTaskStarted) {
            LogFileManager.writeLog("任务已在执行中，忽略重复启动")
            return
        }

        val taskList = DatabaseWrapper.loadAllTask()
        if (!validateTaskListForStart(taskList)) {
            return
        }

        LogFileManager.writeLog("开始执行每日任务")
        updateTaskState(true)
        scheduleFirstTask()
        listener.onTaskStarted()
    }

    private fun validateTaskListForStart(taskList: List<DailyTaskBean>): Boolean {
        if (taskList.isEmpty()) {
            listener.onTaskExecutionError("启动任务失败，请先添加任务时间点")
            return false
        }

        if (taskList.getTaskIndex() == INVALID_TASK_INDEX) {
            LogFileManager.writeLog("今日任务已全部执行完毕，忽略启动")
            listener.onTaskCompleted()
            notifyServiceTaskCompleted()
            return false
        }

        return true
    }

    /**
     * 调度第一个任务
     */
    private fun scheduleFirstTask() {
        cancelScheduledTasks()
        mainHandler.post(dailyTaskRunnable)
    }

    /**
     * 重新调度下一个任务
     */
    private fun rescheduleNextTask() {
        cancelScheduledTasks()
        mainHandler.post(dailyTaskRunnable)
    }

    /**
     * 取消所有已调度的任务
     */
    private fun cancelScheduledTasks() {
        mainHandler.removeCallbacks(dailyTaskRunnable)
    }

    /**
     * 向倒计时服务发送指令
     */
    private fun sendCommandToService(action: String, taskIndex: Int = -1, seconds: Int = 0) {
        Intent(context, CountDownTimerService::class.java).apply {
            this.action = action
            if (taskIndex != INVALID_TASK_INDEX) {
                putExtra(CountDownTimerService.EXTRA_TASK_INDEX, taskIndex)
            }
            if (seconds > NO_SECONDS_DELAY) {
                putExtra(CountDownTimerService.EXTRA_SECONDS, seconds)
            }

            // 使用startService，不是startForegroundService，不会触发onCreate，不会要求重新startForeground
            context.startService(this)
        }
    }

    /**
     * 通知服务任务已完成
     */
    private fun notifyServiceTaskCompleted() {
        sendCommandToService(CountDownTimerService.ACTION_COMPLETED_DAILY_TASK)
    }

    /**
     * 取消服务的倒计时
     */
    private fun cancelCountdownService() {
        sendCommandToService(CountDownTimerService.ACTION_CANCEL_COUNTDOWN)
    }

    /**
     * 启动倒计时服务
     */
    private fun startCountdownService(taskIndex: Int, seconds: Int) {
        sendCommandToService(CountDownTimerService.ACTION_START_COUNTDOWN, taskIndex, seconds)
    }

    /**
     * 更新任务状态
     */
    private fun updateTaskState(started: Boolean) {
        isTaskStarted = started
    }

    /**
     * 当日串行任务Runnable
     * 负责按顺序执行每日任务
     */
    private val dailyTaskRunnable = Runnable {
        try {
            executeCurrentTask()
        } catch (e: IndexOutOfBoundsException) {
            handleTaskExecutionError("任务数组访问越界: ${e.message}")
        } catch (e: Exception) {
            handleTaskExecutionError("执行任务时发生异常: ${e.message}")
        }
    }

    /**
     * 执行当前任务
     */
    private fun executeCurrentTask() {
        val taskList = DatabaseWrapper.loadAllTask()
        val currentIndex = taskList.getTaskIndex()

        if (currentIndex == INVALID_TASK_INDEX) {
            handleAllTasksCompleted()
            return
        }

        if (!isIndexValid(currentIndex, taskList.size)) {
            handleInvalidTaskIndex(currentIndex, taskList.size)
            return
        }

        processTask(taskList, currentIndex)
    }

    private fun handleAllTasksCompleted() {
        LogFileManager.writeLog("今日任务已全部执行完毕")
        cancelScheduledTasks()
        updateTaskState(false)
        listener.onTaskCompleted()
        notifyServiceTaskCompleted()
    }

    private fun isIndexValid(index: Int, listSize: Int): Boolean {
        return index in 0 until listSize
    }

    private fun handleInvalidTaskIndex(index: Int, listSize: Int) {
        val errorMsg = "任务索引超出范围: $index, 数组大小: $listSize"
        handleTaskExecutionError(errorMsg)
    }

    /**
     * 处理单个任务的执行
     */
    private fun processTask(taskList: List<DailyTaskBean>, index: Int) {
        val task = taskList[index]
        val taskNumber = index + 1

        LogFileManager.writeLog("执行任务，任务编号: $taskNumber，时间: ${task.time}")

        val (realTime, timeSeconds) = task.diffCurrent()

        listener.onTaskExecuting(taskNumber, task, realTime)

        startCountdownService(taskNumber, timeSeconds)
    }

    private fun handleTaskExecutionError(message: String) {
        LogFileManager.writeLog(message)
        updateTaskState(false)
        cancelScheduledTasks()
        cancelCountdownService()
        listener.onTaskExecutionError(message)
    }
}
