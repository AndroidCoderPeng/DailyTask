package com.pengxh.daily.app.extensions

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
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
        EventBus.getDefault().post(ApplicationEvent.StopDailyTask)
        return
    }

    // 钉钉且用户开启了"直接跳转打卡界面"，走 scheme 落地到考勤 H5
    val directAttendance = SaveKeyValues.getValue(Constant.DIRECT_ATTENDANCE_KEY, false) as Boolean
    if (targetApp == Constant.DING_DING && directAttendance) {
        val launched = tryOpenDingTalkAttendance()
        if (launched) {
            if (needCountDown) {
                EventBus.getDefault().post(ApplicationEvent.StartCountdownTime(false))
            }
            return
        }
        // scheme 失败则 fallback 到 Launcher
        Log.w("Ex-Context", "openApplication: 钉钉打卡 scheme 失败，回退到 Launcher")
    }

    // 通用：跳转目标应用 Launcher Activity
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
        startActivity(intent)

        // 在目标应用界面更新悬浮窗倒计时
        if (needCountDown) {
            EventBus.getDefault().post(ApplicationEvent.StartCountdownTime(false))
        }
    } else {
        Log.w("Ex-Context", "openApplication: 未找到目标应用的 Launcher Activity，包名：$targetApp")
        EventBus.getDefault().post(ApplicationEvent.StopDailyTask)
    }
}

fun Context.openApplication() {
    val targetApp = Constant.getTargetApp()
    if (!isApplicationExist(targetApp)) {
        return
    }

    // 钉钉且用户开启了"直接跳转打卡界面"，走 scheme 落地到考勤 H5
    val directAttendance = SaveKeyValues.getValue(Constant.DIRECT_ATTENDANCE_KEY, false) as Boolean
    if (targetApp == Constant.DING_DING && directAttendance) {
        val launched = tryOpenDingTalkAttendance()
        if (launched) {
            EventBus.getDefault().post(ApplicationEvent.StartCountdownTime(true))
            return
        }
        Log.w("Ex-Context", "openApplication: 钉钉打卡 scheme 失败，回退到 Launcher")
    }

    // 通用：跳转目标应用 Launcher Activity
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
        startActivity(intent)

        // 在目标应用界面更新悬浮窗倒计时
        EventBus.getDefault().post(ApplicationEvent.StartCountdownTime(true))
    } else {
        Log.w("Ex-Context", "openApplication: 未找到目标应用的 Launcher Activity，包名：$targetApp")
    }
}

/**
 * 通过 scheme 直接跳转钉钉考勤打卡页
 * @return true 表示 scheme 启动成功
 */
@SuppressLint("UseKtx")
private fun Context.tryOpenDingTalkAttendance(): Boolean {
    return try {
        val uri = Uri.parse("dingtalk://dingtalkclient/page/link?url=https://attend.dingtalk.com/attend/index.html")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        Log.d("Ex-Context", "tryOpenDingTalkAttendance: scheme 启动成功")
        true
    } catch (e: ActivityNotFoundException) {
        Log.w("Ex-Context", "tryOpenDingTalkAttendance: scheme 不可用 -> ${e.message}")
        false
    }
}
