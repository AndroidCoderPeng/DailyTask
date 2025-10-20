package com.pengxh.daily.app.utils

import com.pengxh.kt.lite.utils.SaveKeyValues
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeKit {

    fun getTodayDate(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        return dateFormat.format(Date())
    }

    fun getResetTaskSeconds(): Int {
        val hour = SaveKeyValues.getValue(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        ) as Int
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        // 设置今天的计划时间
        val todayTargetMillis = calendar.clone() as Calendar
        todayTargetMillis.set(Calendar.HOUR_OF_DAY, hour)
        todayTargetMillis.set(Calendar.MINUTE, 0)
        todayTargetMillis.set(Calendar.SECOND, 0)
        todayTargetMillis.set(Calendar.MILLISECOND, 0)

        // 根据当前时间决定计算哪一天的计划时间
        val targetMillis = if (currentHour < hour) {
            // 今天还没到计划时间
            todayTargetMillis.timeInMillis
        } else {
            // 今天已经过了计划时间，计算明天的
            todayTargetMillis.add(Calendar.DATE, 1)
            todayTargetMillis.timeInMillis
        }

        val delta = (targetMillis - System.currentTimeMillis()) / 1000
        return delta.toInt()
    }
}