package com.pengxh.kt.lite.extensions

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

//将content路径转为path
fun Uri.realFilePath(context: Context): String {
    var path = ""
    when (scheme) {
        ContentResolver.SCHEME_FILE -> path = this.path ?: ""

        ContentResolver.SCHEME_CONTENT -> {
            context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val displayName = if (nameIndex != -1) {
                        cursor.getString(nameIndex)
                    } else {
                        // 尝试MediaStore方式获取
                        val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
                        val mediaCursor = context.contentResolver.query(
                            this, projection, null, null, null
                        )
                        mediaCursor?.use { c ->
                            if (c.moveToFirst()) {
                                val idx = c.getColumnIndexOrThrow(
                                    MediaStore.Images.Media.DISPLAY_NAME
                                )
                                c.getString(idx)
                            } else {
                                "temp_file_${System.currentTimeMillis()}"
                            }
                        } ?: "temp_file_${System.currentTimeMillis()}"
                    }

                    context.contentResolver.openInputStream(this)?.use { inputStream ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val uniqueFileName = "${System.currentTimeMillis()}_$displayName"
                            val cache = File(context.cacheDir, uniqueFileName)
                            FileOutputStream(cache).use { output ->
                                inputStream.copyTo(output)
                            }
                            path = cache.absolutePath
                        } else {
                            // 处理 Android 10 以下的情况
                            val projection = arrayOf(MediaStore.Images.Media.DATA)
                            context.contentResolver.query(
                                this, projection, null, null, null
                            )?.use { cursor ->
                                val dataIndex = cursor.getColumnIndexOrThrow(
                                    MediaStore.Images.Media.DATA
                                )
                                if (cursor.moveToFirst()) {
                                    path = cursor.getString(dataIndex)
                                }
                            }
                        }
                    }
                }
            } ?: throw IllegalStateException("Cursor is null")
        }

        else -> throw IllegalArgumentException("Unsupported scheme: $scheme")
    }
    return path
}