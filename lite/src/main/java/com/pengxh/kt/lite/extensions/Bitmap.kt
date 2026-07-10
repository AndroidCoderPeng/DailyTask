package com.pengxh.kt.lite.extensions

import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Base64

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

/**
 * 旋转图片
 */
fun Bitmap.rotateImage(angle: Float): Bitmap {
    // 确保在0到360度之间
    val rotatedAngle = (angle % 360 + 360) % 360
    if (rotatedAngle == 0f) {
        return this
    }

    val matrix = Matrix()
    matrix.postRotate(rotatedAngle)
    // 创建新的图片
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/**
 * 获取图片base64编码
 *
 * 如果是上传到服务器，编码格式为：Base64.NO_WRAP
 *
 * 如果是本地使用，编码格式为：Base64.DEFAULT
 *
 * 默认：Base64.NO_WRAP
 */
fun Bitmap.toBase64(): String {
    try {
        val outputStream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 100, outputStream) // 不压缩
        val bitmapBytes = outputStream.toByteArray()
        outputStream.flush()
        outputStream.close()
        return Base64.getEncoder().encodeToString(bitmapBytes)
    } catch (e: IOException) {
        e.printStackTrace()
    }
    return ""
}