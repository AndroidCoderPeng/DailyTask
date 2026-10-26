package com.pengxh.daily.app.utils

sealed class DayFlag(val label: String, val shouldSkip: Boolean) {
    data object SubstituteWorkday : DayFlag("补班日", false)
    data object Holiday : DayFlag("节假日", true)
    data object RestDay : DayFlag("休息日", true)
    data object Workday : DayFlag("工作日", false)
}

sealed class TipsEvent {
    /**
     * 按当日类型跳过
     * */
    data class Skip(val dayFlag: DayFlag) : TipsEvent()

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