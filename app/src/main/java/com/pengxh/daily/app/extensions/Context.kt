package com.pengxh.daily.app.extensions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.ui.MainActivity
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.MessageEvent
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertMessageDialog
import org.greenrobot.eventbus.EventBus

/**
 * 检测通知监听服务是否被授权
 * */
fun Context.notificationEnable(): Boolean {
    val packages = NotificationManagerCompat.getEnabledListenerPackages(this)
    return packages.contains(this.packageName)
}

/**
 * 打开指定包名的apk
 */
fun Context.openApplication(needEmail: Boolean) {
    val packageName = SaveKeyValues.getValue(Constant.TARGET_APP_PACKAGE_KEY, "") as String
    if (packageName == "") {
        AlertMessageDialog.Builder()
            .setContext(this)
            .setTitle("温馨提示")
            .setMessage("未指定目标软件，无法打开")
            .setPositiveButton("知道了")
            .setOnDialogButtonClickListener(object :
                AlertMessageDialog.OnDialogButtonClickListener {
                override fun onConfirmClick() {

                }
            }).build().show()
        return
    }

    FloatingWindowService.weakReferenceHandler?.apply {
        sendEmptyMessage(Constant.SHOW_FLOATING_WINDOW_CODE)
    }
    /**跳转钉钉开始*****************************************/
    val resolveIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setPackage(packageName)
    }
    val apps = this.packageManager.queryIntentActivities(resolveIntent, 0)
    //前面已经判断过钉钉是否安装，所以此处一定有值
    val info = apps.first()
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
    }
    this.startActivity(intent)
    /**跳转钉钉结束*****************************************/
    if (needEmail) {
        EventBus.getDefault().post(MessageEvent(Constant.START_COUNT_DOWN_TIMER_CODE))
    }
}

fun Context.backToMainActivity() {
    EventBus.getDefault().post(MessageEvent(Constant.CANCEL_COUNT_DOWN_TIMER_CODE))
    val backToHome = SaveKeyValues.getValue(Constant.BACK_TO_HOME_KEY, false) as Boolean
    if (backToHome) {
        //模拟点击Home键
        val home = Intent(Intent.ACTION_MAIN).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
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
}