package com.pengxh.daily.app.utils

import android.os.Handler
import com.pengxh.daily.app.extensions.diffCurrent
import com.pengxh.daily.app.extensions.getTaskIndex
import com.pengxh.daily.app.service.CountDownTimerService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean

/**
 * 任务调度器
 *
 * 职责：
 * 1. 管理任务启动/停止状态
 * 2. 执行每日任务调度逻辑
 * 3. 协调倒计时服务和UI更新
 *
 * @param mainHandler 主线程Handler
 * @param listener 任务状态回调
 */
class TaskScheduler(
    private val mainHandler: Handler,
    private val listener: TaskStateListener
) {
    private var countDownTimerService: CountDownTimerService? = null
    private var isTaskStarted = false

    // 任务状态回调
    interface TaskStateListener {
        fun onTaskStarted()
        fun onTaskStopped()
        fun onTaskCompleted()
        fun onTaskExecuting(taskIndex: Int, task: DailyTaskBean, realTime: String)
        fun onTaskExecutionError(message: String)
    }

    fun setCountDownTimerService(service: CountDownTimerService?) {
        this.countDownTimerService = service
    }

    fun isTaskStarted(): Boolean = isTaskStarted

    /**
     * 启动任务
     */
    fun startTask() {
        if (isTaskStarted) {
            LogFileManager.writeLog("任务已在执行中，忽略重复启动")
            return
        }

        val taskBeans = DatabaseWrapper.loadAllTask()
        if (taskBeans.isEmpty()) {
            listener.onTaskExecutionError("启动任务失败，请先添加任务时间点")
            return
        }

        if (taskBeans.getTaskIndex() == -1) {
            LogFileManager.writeLog("今日任务已全部执行完毕，忽略启动")
            listener.onTaskCompleted()
            countDownTimerService?.updateDailyTaskState()
            return
        }

        if (countDownTimerService == null) {
            listener.onTaskExecutionError("启动任务失败，倒计时服务未就绪，请稍后重试")
            return
        }

        LogFileManager.writeLog("开始执行每日任务")

        // 更新状态标志
        isTaskStarted = true

        // 启动任务调度，先移除所有未执行的 Runnable，避免重复投递
        mainHandler.removeCallbacks(dailyTaskRunnable)
        mainHandler.post(dailyTaskRunnable)

        // 通知状态变更
        listener.onTaskStarted()
    }

    /**
     * 停止任务
     */
    fun stopTask() {
        LogFileManager.writeLog("停止执行每日任务")
        isTaskStarted = false

        // 取消任务调度
        mainHandler.removeCallbacks(dailyTaskRunnable)

        // 取消服务中的倒计时
        countDownTimerService?.cancelCountDown()

        // 通知状态变更
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
        // 先移除所有未执行的 Runnable，避免重复投递
        mainHandler.removeCallbacks(dailyTaskRunnable)
        mainHandler.post(dailyTaskRunnable)
    }

    /**
     * 当日串行任务Runnable
     * 负责按顺序执行每日任务
     */
    private val dailyTaskRunnable = object : Runnable {
        override fun run() {
            try {
                val taskBeans = DatabaseWrapper.loadAllTask()
                val index = taskBeans.getTaskIndex()
                if (index == -1) {
                    LogFileManager.writeLog("今日任务已全部执行完毕")
                    mainHandler.removeCallbacks(this)
                    isTaskStarted = false

                    // 通知任务完成
                    listener.onTaskCompleted()

                    // 更新服务状态
                    countDownTimerService?.updateDailyTaskState()
                    return
                }

                // 二次验证索引是否在有效范围内
                if (index < 0 || index >= taskBeans.size) {
                    val errorMsg = "任务索引超出范围: $index, 数组大小: ${taskBeans.size}"
                    failExecution(errorMsg)
                    return
                }

                LogFileManager.writeLog("执行任务，任务index是: $index，时间是: ${taskBeans[index].time}")
                val task = taskBeans[index]
                val taskIndex = index + 1

                // 计算时间差
                val (realTime, timeSeconds) = task.diffCurrent()

                // 通知UI更新
                listener.onTaskExecuting(taskIndex, task, realTime)

                // 启动倒计时
                countDownTimerService?.startCountDown(taskIndex, timeSeconds)
            } catch (e: IndexOutOfBoundsException) {
                val errorMsg = "任务数组访问越界: ${e.message}"
                failExecution(errorMsg)
            } catch (e: Exception) {
                val errorMsg = "执行任务时发生异常: ${e.message}"
                failExecution(errorMsg)
            }
        }
    }

    private fun failExecution(message: String) {
        LogFileManager.writeLog(message)
        isTaskStarted = false
        mainHandler.removeCallbacks(dailyTaskRunnable)
        countDownTimerService?.cancelCountDown()
        listener.onTaskExecutionError(message)
    }

    fun destroy() {
        mainHandler.removeCallbacks(dailyTaskRunnable)
        countDownTimerService = null
    }
}
