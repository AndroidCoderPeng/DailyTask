package com.pengxh.daily.app.utils

sealed class TipsEvent {
    /**
     * 节假日/周末跳过
     * */
    data object Skip : TipsEvent()

    /**
     * 正在执行某个任务
     * */
    data class Executing(
        val index: Int,
        val total: Int,
        val actualTime: String,
        val plannedTime: String
    ) : TipsEvent()

    /**
     * 当日所有任务已完成
     * */
    data object Completed : TipsEvent()
}