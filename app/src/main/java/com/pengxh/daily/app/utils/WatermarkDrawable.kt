package com.pengxh.daily.app.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withRotation
import com.pengxh.kt.lite.extensions.sp2px
import kotlin.math.sqrt

class WatermarkDrawable(private val context: Context, private val watermark: String) : Drawable() {

    private val paint by lazy {
        Paint().apply {
            textSize = 15f.sp2px(context)
            color = "#FFDFDFDF".toColorInt()
        }
    }

    override fun draw(canvas: Canvas) {
        val width = bounds.right
        val height = bounds.bottom

        canvas.drawColor("#40F3F5F9".toColorInt())
        canvas.withRotation(-30f) {
            val textWidth = paint.measureText(watermark)
            val textHeight = paint.fontSpacing.toInt()

            // 使用屏幕对角线长度作为绘制范围，确保旋转后也能完全覆盖
            val diagonal = sqrt((width * width + height * height).toDouble()).toFloat()

            // 垂直方向：从负值开始，确保左上角也有水印
            var y = -diagonal
            var rowIndex = 0
            while (y < diagonal) {
                // 水平方向：交错排列
                val offset = if (rowIndex % 2 == 0) 0f else textWidth / 3
                var x = -diagonal + offset

                // 绘制这一行的水印
                val horizontalSpacing = diagonal * 0.35f
                repeat((0..3).count()) {
                    canvas.drawText(watermark, x, y, paint)
                    x += horizontalSpacing
                }

                y += textHeight + 180
                rowIndex++
            }
        }
    }

    override fun setAlpha(alpha: Int) {
    }

    override fun getOpacity(): Int {
        return PixelFormat.UNKNOWN
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
    }
}