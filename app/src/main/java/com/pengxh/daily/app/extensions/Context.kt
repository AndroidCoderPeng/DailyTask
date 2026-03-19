package com.pengxh.daily.app.extensions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.extensions.show
import org.greenrobot.eventbus.EventBus

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
 * 打开指定包名的apk
 * @param needCountDown 是否需要倒计时
 */
fun Context.openApplication(needCountDown: Boolean) {
    val targetApp = Constant.getTargetApp()
    Log.d("Ex-Context", "openApplication: $targetApp")
    if (!isApplicationExist(targetApp)) {
        "未安装指定的目标软件，无法执行任务".show(this)
        // 停止任务
        EventBus.getDefault().post(ApplicationEvent.StopDailyTask)
        return
    }

    // 跳转目标应用
    val intent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        setPackage(targetApp)
    }
    val activities = packageManager.queryIntentActivities(intent, 0)
    if (activities.isNotEmpty()) {
        val info = activities.first()
        intent.component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
        startActivity(intent)
    }

    // 在目标应用界面更新悬浮窗倒计时
    if (needCountDown) {
        EventBus.getDefault().post(ApplicationEvent.StartCountdownTime)
    }
}