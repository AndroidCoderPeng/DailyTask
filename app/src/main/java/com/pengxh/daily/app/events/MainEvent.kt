package com.pengxh.daily.app.events

import com.pengxh.daily.app.vm.MainViewModel

/**
 * 主页面一次性事件
 *
 * 只放"需要 UI 做出反应"的动作，
 * 列表数据本身走 [MainViewModel.taskListState]，不在这里重复传。
 * */
sealed class MainEvent {

    /** 任务时间点已存在，无法重复新增 */
    data object TaskTimeExists : MainEvent()

    /** 新增任务成功 */
    data object TaskAdded : MainEvent()

    /** 导入完成 */
    data class ImportSuccess(val count: Int, val warnings: List<String>) : MainEvent()

    /** 导入失败 */
    data class ImportError(val message: String) : MainEvent()

    /** 导出成功 */
    data class ExportSuccess(val json: String) : MainEvent()
}