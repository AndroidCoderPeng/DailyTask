package com.pengxh.daily.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.createBitmap

class NativeWatermarkView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var watermarkBitmap: Bitmap? = null
    private var configPtr: Long = 0

    init {
        // 获取Native配置
        try {
            configPtr = DailyTask.getWatermarkConfig()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            createWatermarkBitmap(w, h)
        }
    }

    private fun createWatermarkBitmap(width: Int, height: Int) {
        watermarkBitmap?.recycle()
        try {
            watermarkBitmap = createBitmap(width, height)
            if (configPtr != 0L) {
                watermarkBitmap?.let {
                    DailyTask.drawWatermark(it, width, height, configPtr)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        watermarkBitmap?.let {
            if (!it.isRecycled) {
                canvas.drawBitmap(it, 0f, 0f, null)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 清理资源
        watermarkBitmap?.recycle()
        watermarkBitmap = null
        if (configPtr != 0L) {
            DailyTask.releaseWatermarkConfig(configPtr)
            configPtr = 0
        }
    }
}