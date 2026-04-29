package com.pengxh.daily.app.extensions

import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.TimeKit
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 找出任务中，实际执行时间最早且晚于当前时间的任务Index
 * */
fun List<DailyTaskBean>.getTaskIndex(): Int {
    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    val currentMillis = System.currentTimeMillis()
    var nextIndex = -1
    var nextMillis = Long.MAX_VALUE
    for ((index, task) in this.withIndex()) {
        //获取当前日期，拼给任务时间，不然不好计算时间差
        val taskTime = "${TimeKit.getTodayDate()} ${task.resolveExecutionTime()}"
        val taskDate = timeFormat.parse(taskTime) ?: continue
        if (taskDate.time > currentMillis && taskDate.time < nextMillis) {
            nextIndex = index
            nextMillis = taskDate.time
        }
    }
    return nextIndex
}
