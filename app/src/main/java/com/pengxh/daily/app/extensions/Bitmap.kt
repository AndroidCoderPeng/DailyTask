package com.pengxh.daily.app.extensions

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 保存图片，不压缩
 */
fun Bitmap.saveImage(imagePath: String, quality: Int = 100) {
    val imageFile = File(imagePath)
    try {
        FileOutputStream(imageFile).use { fos ->
            compress(Bitmap.CompressFormat.JPEG, quality, fos)
        }
    } catch (e: IOException) {
        e.printStackTrace()
    }
}