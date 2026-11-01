package com.pengxh.daily.app.utils

import com.pengxh.daily.app.sqlite.bean.DailyTaskBean

/**
 * 任务列表状态
 *
 * 区分"还没加载完"和"加载完但是空的"：
 * 布局里 emptyView 默认是 gone，如果初始态直接给空列表，
 * 页面会先闪一下空态再显示列表。
 * */
sealed interface TaskListState {

    /** 尚未加载完成，保持布局默认状态 */
    data object Initial : TaskListState

    /** 加载完成 */
    data class Loaded(val tasks: List<DailyTaskBean>) : TaskListState
}