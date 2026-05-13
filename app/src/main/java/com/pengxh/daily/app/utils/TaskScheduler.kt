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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isTaskStarted = false

    // 任务状态回调
    interface TaskStateListener {
        fun onTaskStarted()
        fun onTaskStopped()
        fun onTaskCompleted()
        fun onTaskExecuting(taskIndex: Int, task: DailyTaskBean, realTime: String)
        fun onTaskExecutionError(message: String)
    }

    fun isTaskStarted(): Boolean = isTaskStarted

    private fun startIntentService(action: String, taskIndex: Int = -1, seconds: Int = 0) {
        Intent(context, CountDownTimerService::class.java).apply {
            this.action = action
            if (taskIndex != -1) {
                putExtra(CountDownTimerService.EXTRA_TASK_INDEX, taskIndex)
            }
            if (seconds > 0) {
                putExtra(CountDownTimerService.EXTRA_SECONDS, seconds)
            }

            // 使用startService，不是startForegroundService，不会触发onCreate，不会要求重新startForeground
            context.startService(this)
        }
    }

    /**
     * 启动任务
     */
    fun startTask() {
        val enabled = SaveKeyValues.getValue(Constant.SKIP_CHINA_HOLIDAY_KEY, false) as Boolean
        if (enabled) {
            val dayInfo = ChinaHolidayCalendar.evaluateToday()
            if (dayInfo.shouldSkip) {
                // 节假日，忽略启动任务
                LogFileManager.writeLog("今日为节假日 ${dayInfo.date}，跳过任务执行")
                listener.onTaskCompleted()
                startIntentService(CountDownTimerService.ACTION_COMPLETED_DAILY_TASK)
                return
            } else {
                if (!dayInfo.hasOfficialAdjustment) {
                    listener.onTaskExecutionError("未配置中国节假日调休表，任务按正常工作日执行")
                }

                // 非节假日，继续执行任务启动逻辑
                internalStartTask()
            }
        } else {
            internalStartTask()
        }
    }

    private fun internalStartTask() {
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
            startIntentService(CountDownTimerService.ACTION_COMPLETED_DAILY_TASK)
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
        startIntentService(CountDownTimerService.ACTION_CANCEL_COUNTDOWN)

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
                    startIntentService(CountDownTimerService.ACTION_COMPLETED_DAILY_TASK)
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
                startIntentService(
                    CountDownTimerService.ACTION_START_COUNTDOWN,
                    taskIndex,
                    timeSeconds
                )
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
        startIntentService(CountDownTimerService.ACTION_CANCEL_COUNTDOWN)
        listener.onTaskExecutionError(message)
    }

    fun destroy() {
        mainHandler.removeCallbacks(dailyTaskRunnable)
    }
}
