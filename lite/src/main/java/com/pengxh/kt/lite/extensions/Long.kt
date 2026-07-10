package com.pengxh.kt.lite.extensions

import java.math.RoundingMode
import java.text.DecimalFormat
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

/**
 * 时间戳转年月日
 */
fun Long.timestampToDate(): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    return dateFormat.format(Date(this))
}

/**
 * 时间戳转时分秒
 */
fun Long.timestampToTime(): String {
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    return dateFormat.format(Date(this))
}

/**
 * 时间戳转分秒
 */
fun Long.millsToTime(): String {
    val dateFormat = SimpleDateFormat("mm:ss", Locale.CHINA)
    return dateFormat.format(Date(this))
}

private const val KB = 1024L
private const val MB = KB * KB
private const val GB = MB * KB
fun Long.formatFileSize(): String {
    val df = DecimalFormat("0.00").apply {
        roundingMode = RoundingMode.HALF_UP
    }

    return when {
        this < 0 -> "Invalid size"
        this < KB -> "${df.format(this.toDouble())} B"
        this < MB -> "${df.format(this.toDouble() / KB)} KB"
        this < GB -> "${df.format(this.toDouble() / MB)} MB"
        else -> "${df.format(this.toDouble() / GB)} GB"
    }
}