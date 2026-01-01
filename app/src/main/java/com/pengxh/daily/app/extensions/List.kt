package com.pengxh.daily.app.extensions

import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.TimeKit
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 找出任务中，第一个时间晚于当前时间的任务Index
 * */
fun List<DailyTaskBean>.getTaskIndex(): Int {
    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    val currentMillis = System.currentTimeMillis()
    for ((index, task) in this.withIndex()) {
        //获取当前日期，拼给任务时间，不然不好计算时间差
        val taskTime = "${TimeKit.getTodayDate()} ${task.time}"
        val taskDate = timeFormat.parse(taskTime) ?: continue
        // 如果任务时间晚于当前时间，则返回该任务的索引
        if (taskDate.time > currentMillis) {
            return index
        }
    }
    return -1
}