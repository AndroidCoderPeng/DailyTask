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

fun DailyTaskBean.resolveExecutionTime(): String {
    val totalSeconds = resolveExecutionSeconds()
    val hour = totalSeconds / 3600
    val minute = (totalSeconds % 3600) / 60
    val second = totalSeconds % 60
    return "${hour.appendZero()}:${minute.appendZero()}:${second.appendZero()}"
}

private fun DailyTaskBean.resolveExecutionSeconds(): Int {
    val needRandom = SaveKeyValues.loadBoolean(Constant.RANDOM_TIME_KEY, true)

    //18:00:59
    val array = this.time.split(":")
    var totalSeconds = array[0].toInt() * 3600 + array[1].toInt() * 60 + array[2].toInt()

    // 随机时间
    if (needRandom) {
        val minuteRange = SaveKeyValues.loadInt(Constant.RANDOM_MINUTE_RANGE_KEY, 5)

        // 生成随机种子, 保证每天的随机时间是一致的
        val key = "${TimeKit.getTodayDate()}|$id|$time|$minuteRange"
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
