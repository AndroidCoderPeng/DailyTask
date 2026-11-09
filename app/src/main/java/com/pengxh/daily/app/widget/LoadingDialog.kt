package com.pengxh.daily.app.widget

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.pengxh.daily.app.R
import com.pengxh.daily.app.extensions.showCustomDialog

object LoadingDialog {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var loadingDialog: AlertDialog? = null

    /**
     * 显示带标题的加载对话框
     * @param activity 上下文Activity
     * @param title 标题
     * @param message 提示消息
     */
    fun show(activity: Activity, title: String, message: String? = null) {
        runOnMain {
            dismiss() // 先关闭已有的dialog
            if (activity.isDestroyed || activity.isFinishing) return@runOnMain
            try {
                loadingDialog = activity.showCustomDialog(
                    R.layout.dialog_loading, false
                ) { view, _ ->
                    view.findViewById<TextView>(R.id.titleView).text = title
                    view.findViewById<TextView>(R.id.messageView).apply {
                        isVisible = !message.isNullOrEmpty()
                        text = message.orEmpty()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 关闭加载对话框，任意线程可调
     */
    fun dismiss() {
        runOnMain {
            loadingDialog?.takeIf { it.isShowing }?.dismiss()
            loadingDialog = null
        }
    }

    private inline fun runOnMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post { action() }
    }
}