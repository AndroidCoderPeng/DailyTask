package com.pengxh.daily.app.extensions

import com.github.gzuliyujiang.wheelpicker.entity.TimeEntity
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.AppConfig
import com.pengxh.daily.app.utils.Constant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Random

/**
 * "HH:mm:ss" → 当天的 TimeEntity
 *
 * 用于时间选择弹窗的初始选中值，解析失败时按 00:00:00 兜底
 * */
fun String.toTimeEntity(): TimeEntity {
    val parts = split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val second = parts.getOrNull(2)?.toIntOrNull() ?: 0
    val date = LocalDate.now()
        .atTime(LocalTime.of(hour, minute, second))
        .atZone(ZoneId.systemDefault())
        .toInstant()
    return TimeEntity.target(Date.from(date))
}

fun DailyTaskBean.resolveExecutionTime(): String {
    val totalSeconds = resolveExecutionSeconds()
    val hour = totalSeconds / 3600
    val minute = (totalSeconds % 3600) / 60
    val second = totalSeconds % 60
    return "%02d:%02d:%02d".format(hour, minute, second)
}

private fun DailyTaskBean.resolveExecutionSeconds(): Int {
    val needRandom = AppConfig.loadBoolean(Constant.RANDOM_TIME_KEY, true)

    //18:00:59
    val array = this.time.split(":")
    var totalSeconds = array[0].toInt() * 3600 + array[1].toInt() * 60 + array[2].toInt()

    // 随机时间
    if (needRandom) {
        val minuteRange =
            AppConfig.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)

        // 生成随机种子, 保证每天的随机时间是一致的
        val key = "${LocalDate.now()}|$id|$time|$minuteRange"
        val seed = key.hashCode().toLong()
        val random = Random(seed)

        val seedMinute = if (minuteRange > 0) random.nextInt(minuteRange) else 0
        val seedSeconds = random.nextInt(60)
        totalSeconds += seedMinute * 60 + seedSeconds

        // 确保不超过当天23:59:59（86399秒）
        totalSeconds = minOf(totalSeconds, 86399) // 第一次边界检查
    }

    return totalSeconds.coerceIn(0, 86399) // 第二次边界检查
}
