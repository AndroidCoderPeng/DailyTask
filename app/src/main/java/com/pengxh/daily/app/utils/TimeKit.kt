package com.pengxh.daily.app.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeKit {

    fun getTodayDate(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        return dateFormat.format(Date())
    }
}