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

    /** 当日任务结果汇总 */
    data class Completed(val message: String? = null) : TipsEvent()
}