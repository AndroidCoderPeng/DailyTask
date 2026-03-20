package com.pengxh.daily.app.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeKit {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    fun getTodayDate(): String {
        return dateFormat.format(Date())
    }

    fun getWeekDateRange(): Pair<String, String> {
        // 获取当前日期
        val currentDate = Date()
        val today = dateFormat.format(currentDate)

        // 获取7天前的日期
        val calendar = java.util.Calendar.getInstance()
        calendar.time = currentDate
        calendar.add(java.util.Calendar.DAY_OF_MONTH, -7)
        val sevenDaysAgo = dateFormat.format(calendar.time)

        return Pair(sevenDaysAgo, today)
    }
}