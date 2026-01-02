package com.pengxh.daily.app.extensions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import com.pengxh.daily.app.ui.MainActivity
import com.pengxh.daily.app.utils.BroadcastManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.MessageType
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertMessageDialog

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
    if (!isApplicationExist(targetApp)) {
        AlertMessageDialog.Builder()
            .setContext(this)
            .setTitle("温馨提醒")
            .setMessage("手机没有安装指定的目标应用软件，无法执行任务")
            .setPositiveButton("知道了")
            .setOnDialogButtonClickListener(object :
                AlertMessageDialog.OnDialogButtonClickListener {
                override fun onConfirmClick() {

                }
            }).build().show()
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
        BroadcastManager.getDefault().sendBroadcast(this, MessageType.START_COUNT_DOWN_TIMER.action)
    }
}

fun Context.backToMainActivity() {
    BroadcastManager.getDefault().sendBroadcast(this, MessageType.CANCEL_COUNT_DOWN_TIMER.action)
    val backToHome = SaveKeyValues.getValue(Constant.BACK_TO_HOME_KEY, false) as Boolean
    if (backToHome) {
        //模拟点击Home键
        val home = Intent(Intent.ACTION_MAIN).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            addCategory(Intent.CATEGORY_HOME)
        }
        startActivity(home)
        Handler(Looper.getMainLooper()).postDelayed({
            launchMainActivity()
        }, 2000)
    } else {
        launchMainActivity()
    }
}

private fun Context.launchMainActivity() {
    val intent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    startActivity(intent)
}