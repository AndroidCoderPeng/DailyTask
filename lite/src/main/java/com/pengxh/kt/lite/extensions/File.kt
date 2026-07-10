package com.pengxh.kt.lite.extensions

import java.io.File
import java.util.Base64

/**
 * 获取图片文件base64编码
 */
fun File.toBase64(): String {
    val imageBytes = readBytes()
    return Base64.getEncoder().encodeToString(imageBytes)
}

/**
 * 递归计算文件夹大小
 */
fun File.calculateSize(): Long {
    var size = 0L
    val files = listFiles()
    if (files != null) {
        for (f in files) {
            size += if (f.isDirectory) {
                f.calculateSize()
            } else {
                f.length()
            }
        }
    }
    return size
}

/**
 * 递归删除文件
 */
fun File.deleteFile() {
    if (isDirectory) {
        val files = listFiles()
        if (files != null) {
            for (f in files) {
                f.deleteFile()
            }
        }
        // 删除空目录
        delete()
    } else if (exists()) {
        delete()
    }
}