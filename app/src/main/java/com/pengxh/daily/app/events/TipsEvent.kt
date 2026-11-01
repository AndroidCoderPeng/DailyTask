package com.pengxh.daily.app.events

import com.pengxh.daily.app.utils.DayFlag

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

    /**
     * 当日任务结果汇总
     *
     * @param message 展示文案
     * @param hasFailure 是否存在失败项，由调度器显式给出，
     *                   不要在 UI 层用 "文案里是否包含某个词" 来推断状态
     * */
    data class Completed(
        val message: String? = null,
        val hasFailure: Boolean = false
    ) : TipsEvent()
}
