package com.pengxh.daily.app.extensions

import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.TimeKit
import java.util.Calendar

/**
 * 从任务列表中找出「下一个即将执行」的任务索引位置
 * */
fun List<DailyTaskBean>.getTaskIndex(): Int {

    val today = TimeKit.getTodayDate()
    val dateParts = today.split("-").map { it.toInt() } // [year, month, day]

    //  比 SimpleDateFormat 更轻量且只调用一次且线程安全
    val calendar = Calendar.getInstance().apply {
        set(dateParts[0], dateParts[1] - 1, dateParts[2], 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val baseMillis = calendar.timeInMillis

    val currentMillis = System.currentTimeMillis()
    var nextIndex = -1
    var nextMillis = Long.MAX_VALUE

    for ((index, task) in this.withIndex()) {
        //获取当前日期，拼给任务时间，不然不好计算时间差
        val execTime = task.resolveExecutionTime()
        val timeParts = execTime.split(":")

        // 纯数学计算：基准 + 时分秒偏移
        val taskMillis = baseMillis +
                timeParts[0].toLong() * 3_600_000L +
                timeParts[1].toLong() * 60_000L +
                timeParts[2].toLong() * 1_000L

        if (taskMillis > currentMillis && taskMillis < nextMillis) {
            nextIndex = index
            nextMillis = taskMillis
        }
    }
    return nextIndex
}
