package com.pengxh.daily.app.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeKit {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    fun getTodayDate(): String {
        return dateFormat.format(Date())
    }
}