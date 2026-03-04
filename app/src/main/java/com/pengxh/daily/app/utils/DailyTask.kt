package com.pengxh.daily.app.utils

object DailyTask {
    init {
        System.loadLibrary("daily_task")
    }

    external fun getWatermarkText(): String
}