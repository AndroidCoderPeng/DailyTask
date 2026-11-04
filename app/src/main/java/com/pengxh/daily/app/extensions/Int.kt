package com.pengxh.daily.app.extensions

import android.content.Context
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * 获取xml颜色值
 */
fun Int.convertColor(context: Context): Int {
    return ContextCompat.getColor(context, this)
}

fun Int.formatTime(): String {
    val total = this.coerceAtLeast(0) // 负数兜底，防止 UI 展示异常
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return String.format(Locale.getDefault(), "%02d小时%02d分钟%02d秒", hours, minutes, secs)
}
