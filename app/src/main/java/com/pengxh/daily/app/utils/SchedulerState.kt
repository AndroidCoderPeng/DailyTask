package com.pengxh.daily.app.utils

import com.pengxh.daily.app.sqlite.bean.DailyTaskBean

/**
 * 调度器对外状态
 * 状态转换：Idle → Executing → Completed  或  Idle → Skipped
 *         Completed/Executing/Skipped → Idle（用户停止）
 */
sealed class SchedulerState {
    /**
     * 空闲
     * */
    data object Idle : SchedulerState()

    /**
     * 节假日跳过
     * */
    data object Skipped : SchedulerState()

    /**
     * 正在执行某个任务
     * */
    data class Executing(
        val taskIndex: Int,
        val task: DailyTaskBean,
        val actualTime: String,
        val totalTasks: Int
    ) : SchedulerState()

    /**
     * 当日所有任务已完成
     * */
    data object Completed : SchedulerState()
}