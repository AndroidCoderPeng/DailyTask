package com.pengxh.daily.app.extensions

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时间戳转年月日时分秒
 */
fun Long.timestampToCompleteDate(): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    return dateFormat.format(Date(this))
}