package com.pengxh.daily.app.utils

import java.time.LocalDate

object Key {
    /**
     * 每日重置标记使用的日期键 —— 跟随设备本地时钟。
     */
    fun todayDateKey(): String = LocalDate.now().toString()
}