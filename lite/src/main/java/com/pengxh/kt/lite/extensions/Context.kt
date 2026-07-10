package com.pengxh.kt.lite.extensions

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.view.WindowInsets
import android.view.WindowManager
import com.pengxh.kt.lite.utils.LiteKitConstant
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 判断是否有网络连接
 * @return
 */
fun Context.isNetworkConnected(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java)
    val network = manager?.activeNetwork ?: return false
    val networkCapabilities = manager.getNetworkCapabilities(network) ?: return false
    return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/**
 * 判断指定包名的应用是否存在
 */
fun Context.isApplicationExist(packageName: String): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (e: PackageManager.NameNotFoundException) {
        e.printStackTrace()
        false
    }
}

inline fun <reified T> Context.navigatePageTo() {
    startActivity(Intent(this, T::class.java))
}

inline fun <reified T> Context.navigatePageTo(value: String) {
    val intent = Intent(this, T::class.java)
    intent.putExtra(LiteKitConstant.INTENT_PARAM_KEY, value)
    startActivity(intent)
}

inline fun <reified T> Context.navigatePageTo(values: ArrayList<String>) {
    val intent = Intent(this, T::class.java)
    intent.putStringArrayListExtra(LiteKitConstant.INTENT_PARAM_KEY, values)
    startActivity(intent)
}

/**
 * @param index     展示图片的角标，从0开始
 * @param imageList
 */
inline fun <reified T> Context.navigatePageTo(index: Int, imageList: ArrayList<String>) {
    val intent = Intent(this, T::class.java)
    intent.putExtra(LiteKitConstant.BIG_IMAGE_INTENT_INDEX_KEY, index)
    intent.putStringArrayListExtra(LiteKitConstant.BIG_IMAGE_INTENT_DATA_KEY, imageList)
    startActivity(intent)
}

/**
 * 获取本地Asserts文件内容
 */
fun Context.readAssetsFile(fileName: String?): String {
    if (fileName.isNullOrBlank()) return ""
    return try {
        assets.open(fileName).use { inputStream ->
            inputStream.bufferedReader().use { bufferedReader ->
                bufferedReader.useLines { lines -> lines.joinToString(separator = "") }
            }
        }
    } catch (e: IOException) {
        e.printStackTrace()
        ""
    }
}

/**
 * 获取屏幕宽度
 *
 * @return
 */
fun Context.getScreenWidth(): Int {
    return resources.displayMetrics.widthPixels
}

/**
 * 获取屏幕高度，兼容Android 11+
 */
fun Context.getScreenHeight(): Int {
    return resources.displayMetrics.heightPixels
}

/**
 * 获取状态栏高度
 * */
@SuppressLint("InternalInsetResource", "DiscouragedApi")
fun Context.getStatusBarHeight(): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val windowMetrics = windowManager.currentWindowMetrics
        val windowInsets = windowMetrics.windowInsets

        val insets = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
        return insets.top
    } else {
        try {
            val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (resourceId > 0) {
                resources.getDimensionPixelSize(resourceId)
            } else {
                0
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return 0
        }
    }
}

/**
 * 获取屏幕密度比值
 */
fun Context.getScreenDensity(): Float {
    return resources.displayMetrics.density
}


fun Context.createLogFile(): File {
    val documentDir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "")
    val timeStamp = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())
    val logFile = File(documentDir.toString() + File.separator + "Log_" + timeStamp + ".txt")
    if (!logFile.exists()) {
        try {
            logFile.createNewFile()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
    return logFile
}

/**
 * 音频编解码：AMR_WB
 *
 * 音频文件格式：.amr
 * */
fun Context.createAudioFile(): File {
    val audioDir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "")
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
    val audioFile = File(audioDir.toString() + File.separator + "AUD_" + timeStamp + ".amr")
    if (!audioFile.exists()) {
        try {
            audioFile.createNewFile()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
    return audioFile
}

fun Context.createVideoFileDir(): File {
    val videoDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "")
    if (!videoDir.exists()) {
        videoDir.mkdir()
    }
    return videoDir
}


fun Context.createDownloadFileDir(): File {
    val downloadDir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "")
    if (!downloadDir.exists()) {
        downloadDir.mkdir()
    }
    return downloadDir
}

fun Context.createImageFileDir(): File {
    val imageDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "")
    if (!imageDir.exists()) {
        imageDir.mkdir()
    }
    return imageDir
}

fun Context.createCompressImageDir(): File {
    val imageDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "CompressImage")
    if (!imageDir.exists()) {
        imageDir.mkdir()
    }
    return imageDir
}