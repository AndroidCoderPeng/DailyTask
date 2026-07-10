package com.pengxh.kt.lite.extensions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import kotlin.math.roundToInt


private const val BITMAP_SCALE = 0.4f
private const val MAX_BLUR_RADIUS = 25f

/**
 * radius 模糊半径，值越大越模糊
 *
 * 取值区间[0,25]
 * */
fun Drawable.toBlurBitmap(context: Context, radius: Float): Bitmap {
    val originalBitmap = toBitmap()
    if (originalBitmap.isRecycled) throw IllegalStateException("Bitmap is already recycled")

    // 计算图片缩小后的长宽
    val width = (originalBitmap.width * BITMAP_SCALE).roundToInt()
    val height = (originalBitmap.height * BITMAP_SCALE).roundToInt()
    val inputBitmap = originalBitmap.scale(width, height, false)

    val outputBitmap = inputBitmap.copy(inputBitmap.config ?: Bitmap.Config.ARGB_8888, true)

    // 初始化 RenderScript
    val rs = RenderScript.create(context)
    val blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

    val tmpIn = Allocation.createFromBitmap(rs, inputBitmap)
    val tmpOut = Allocation.createFromBitmap(rs, outputBitmap)

    blurScript.setRadius(radius.coerceAtMost(MAX_BLUR_RADIUS))
    blurScript.setInput(tmpIn)
    blurScript.forEach(tmpOut)

    tmpOut.copyTo(outputBitmap)

    // 释放资源
    tmpIn.destroy()
    tmpOut.destroy()
    blurScript.destroy()
    rs.destroy()
    return outputBitmap
}