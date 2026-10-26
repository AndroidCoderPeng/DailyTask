package com.pengxh.daily.app.extensions

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.extensions.show

/**
 * 检测通知监听服务是否被授权
 * */
fun Context.notificationEnable(): Boolean {
    val packages = NotificationManagerCompat.getEnabledListenerPackages(this)
    return packages.contains(packageName)
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

/**
 * 打开指定包名的 apk，之后执行回调
 *
 * @param onOpened 目标 App 成功打开后执行的回调（如启动超时计时器）
 * @return 是否成功启动目标 App
 */
fun Context.openApplication(onOpened: (() -> Unit)? = null): Boolean {
    val targetApp = Constant.getTargetApp()
    Log.d("Ex-Context", "openApplication: $targetApp")
    if (!isApplicationExist(targetApp)) {
        "未安装指定的目标软件，无法执行任务".show(this)
        return false
    }

    // 跳转目标应用
    val intent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        setPackage(targetApp)
    }
    val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        packageManager.queryIntentActivities(intent, 0)
    }
    if (activities.isNotEmpty()) {
        val info = activities.first()
        intent.component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
        return try {
            startActivity(intent)
            onOpened?.invoke()
            true
        } catch (e: ActivityNotFoundException) {
            Log.e("Ex-Context", "Target app launch activity disappeared", e)
            "无法启动指定的目标软件".show(this)
            false
        } catch (e: SecurityException) {
            Log.e("Ex-Context", "Target app launch was denied", e)
            "启动指定的目标软件失败".show(this)
            false
        }
    } else {
        "未找到目标应用的启动入口，包名：$targetApp".show(this)
        return false
    }
}
