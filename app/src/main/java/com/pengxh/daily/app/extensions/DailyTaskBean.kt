package com.pengxh.daily.app.extensions

import com.github.gzuliyujiang.wheelpicker.entity.TimeEntity
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.TimeKit
import com.pengxh.kt.lite.extensions.appendZero
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Random

fun DailyTaskBean.convertToTimeEntity(): TimeEntity {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    val date = dateFormat.parse("${TimeKit.getTodayDate()} ${this.time}")!!
    return TimeEntity.target(date)
}

fun DailyTaskBean.diffCurrent(): Pair<String, Int> {
    val newTime = resolveExecutionTime()

    //获取当前日期，计算时间差
    val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    val taskDateTime = "${TimeKit.getTodayDate()} $newTime"
    val taskDate = simpleDateFormat.parse(taskDateTime) ?: return Pair(newTime, 0)
    val currentMillis = System.currentTimeMillis()
    val diffSeconds = (taskDate.time - currentMillis) / 1000
    return Pair(newTime, diffSeconds.toInt())
}

fun DailyTaskBean.resolveExecutionTime(): String {
    val totalSeconds = resolveExecutionSeconds()
    val hour = totalSeconds / 3600
    val minute = (totalSeconds % 3600) / 60
    val second = totalSeconds % 60
    return "${hour.appendZero()}:${minute.appendZero()}:${second.appendZero()}"
}

private fun DailyTaskBean.resolveExecutionSeconds(): Int {
    val needRandom = SaveKeyValues.getValue(Constant.RANDOM_TIME_KEY, true) as Boolean

    //18:00:59
    val array = this.time.split(":")
    var totalSeconds = array[0].toInt() * 3600 + array[1].toInt() * 60 + array[2].toInt()

    // 随机时间
    if (needRandom) {
        val minuteRange = SaveKeyValues.getValue(Constant.RANDOM_MINUTE_RANGE_KEY, 5) as Int
        val random = Random(buildDailyExecutionSeed(minuteRange))

        val seedMinute = if (minuteRange > 0) random.nextInt(minuteRange) else 0
        val seedSeconds = random.nextInt(60)
        totalSeconds += seedMinute * 60 + seedSeconds

        // 确保不超过当天23:59:59（86399秒）
        totalSeconds = minOf(totalSeconds, 86399)
    }

    return totalSeconds.coerceIn(0, 86399)
}

private fun DailyTaskBean.buildDailyExecutionSeed(minuteRange: Int): Long {
    val key = "${TimeKit.getTodayDate()}|$id|$time|$minuteRange"
    return key.hashCode().toLong()
}
