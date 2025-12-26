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
import com.pengxh.daily.app.utils.Constant
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
 * 打开指定包名的apk
 */
fun Context.openApplication(needCountDown: Boolean, isRemoteCommand: Boolean) {
    val isContains = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                Constant.TARGET_APP, PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            packageManager.getPackageInfo(Constant.TARGET_APP, 0)
        }
        true
    } catch (e: PackageManager.NameNotFoundException) {
        e.printStackTrace()
        false
    }
    if (!isContains) {
        AlertMessageDialog.Builder()
            .setContext(this)
            .setTitle("温馨提醒")
            .setMessage("手机没有安装《钉钉》软件，无法自动打卡")
            .setPositiveButton("知道了")
            .setOnDialogButtonClickListener(object :
                AlertMessageDialog.OnDialogButtonClickListener {
                override fun onConfirmClick() {

                }
            }).build().show()
        return
    }

    sendBroadcast(Intent(Constant.BROADCAST_SHOW_FLOATING_WINDOW_ACTION))
    /**跳转钉钉开始*****************************************/
    val intent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        setPackage(Constant.TARGET_APP)
    }
    val activities = packageManager.queryIntentActivities(intent, 0)
    if (activities.isNotEmpty()) {
        val info = activities.first()
        intent.component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
        startActivity(intent)
    }
    /**跳转钉钉结束*****************************************/
    if (needCountDown) {
        sendBroadcast(Intent(Constant.BROADCAST_START_COUNT_DOWN_TIMER_ACTION))
    } else {
        // 如果是远程指令启动钉钉，那么就不必启动循环任务，单次任务，直接打开钉钉即可
        SaveKeyValues.putValue(Constant.NEED_START_TASK_KEY, !isRemoteCommand)
    }
}

fun Context.backToMainActivity() {
    val needNext = SaveKeyValues.getValue(Constant.NEED_START_TASK_KEY, false) as Boolean
    if (needNext) {
        sendBroadcast(Intent(Constant.BROADCAST_CANCEL_COUNT_DOWN_TIMER_ACTION))
    }
    val backToHome = SaveKeyValues.getValue(Constant.BACK_TO_HOME_KEY, false) as Boolean
    if (backToHome) {
        //模拟点击Home键
        val home = Intent(Intent.ACTION_MAIN).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    startActivity(intent)
    sendBroadcast(Intent(Constant.BROADCAST_SHOW_MASK_VIEW_ACTION))
}