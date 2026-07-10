package com.pengxh.kt.lite.extensions

import android.content.Context
import android.widget.Toast
import com.google.gson.Gson
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/**
 * 手动换行
 * */
fun String.wrapLine(length: Int): String {
    // 单行最大显示15个汉字
    val step = if (length <= 0) {
        15
    } else {
        length
    }

    if (isBlank()) {
        return this
    }

    val builder = StringBuilder()
    var start = 0
    while (start < length) {
        val end = minOf(step, length)
        builder.append(substring(start, end))
        if (end < length) {
            builder.append("\r\n")
        }
        start += step
    }

    return builder.toString()
}

/**
 * 时间转时间戳
 */
fun String.dateToTimestamp(): Long {
    try {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        val date = dateFormat.parse(this)
        return date?.time ?: 0
    } catch (e: ParseException) {
        e.printStackTrace()
    }
    return 0
}

/**
 * 时间差-小时
 * */
fun String.diffCurrentTime(): Int {
    if (isBlank()) {
        return 0
    }
    val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    val date = simpleDateFormat.parse(this) ?: return 0
    val diff = abs(System.currentTimeMillis() - date.time)
    return (diff / (3600000)).toInt()
}

/**
 * 判断输入的是否是数字
 */
fun String.isNumber(): Boolean {
    val regex = Regex("([-+])?\\d+(\\.\\d+)?")
    return matches(regex)
}

/**
 * 判断输入的是否是数字和字母
 */
fun String.isLetterAndDigit(): Boolean {
    var isDigit = false
    var isLetter = false
    for (i in indices) {
        if (Character.isDigit(this[i])) {
            isDigit = true
        } else if (Character.isLetter(this[i])) {
            isLetter = true
        }
    }
    return isDigit && isLetter
}

/**
 * 判断是否为汉字
 */
fun String.isChinese(): Boolean {
    if (isNotEmpty()) {
        val regex = Regex("[\\u4e00-\\u9fa5]+")
        return matches(regex)
    }
    return false
}


fun String.isPhoneNumber(): Boolean {
    if (isBlank()) return false
    val regex = Regex("^1[3-9]\\d{9}\$")
    return length == 11 && matches(regex)
}

/**
 * 匹配邮箱地址
 */
fun String.isEmail(): Boolean {
    return if (isBlank()) {
        false
    } else {
        val regExp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\$"
        val regex = Regex(regExp)
        matches(regex)
    }
}

fun String.writeToFile(file: File) {
    try {
        val fileWriter = FileWriter(file, true)
        val writer = BufferedWriter(fileWriter)
        writer.write(this)
        writer.newLine() //换行
        writer.flush()
        writer.close()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}

fun String.show(context: Context) {
    Toast.makeText(context, this, Toast.LENGTH_SHORT).show()
}

inline fun <reified T> unpackingResponse(response: String): T {
    return Gson().fromJson(response, T::class.java)
}

inline fun <reified T> String.unpacking(): T {
    return unpackingResponse(this)
}