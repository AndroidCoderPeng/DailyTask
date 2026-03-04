package com.pengxh.daily.app.utils

import android.graphics.Bitmap

object DailyTask {
    init {
        System.loadLibrary("daily_task")
    }

    external fun getWatermarkConfig(): Long

    external fun releaseWatermarkConfig(ptr: Long)

    external fun drawWatermark(bitmap: Bitmap, width: Int, height: Int, ptr: Long)

    external fun getWatermarkText(): String

    external fun applicationCopyright(): String
}