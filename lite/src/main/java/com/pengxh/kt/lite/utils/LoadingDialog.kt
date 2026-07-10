package com.pengxh.kt.lite.utils

import android.app.Activity
import android.app.ProgressDialog

object LoadingDialog {
    private var loadingDialog: ProgressDialog? = null

    /**
     * 显示加载对话框
     * @param activity 上下文Activity
     * @param message 提示消息
     */
    fun show(activity: Activity, message: String) {
        dismiss() // 先关闭已有的dialog
        if (!activity.isDestroyed && !activity.isFinishing) {
            try {
                loadingDialog = ProgressDialog(activity).apply {
                    setMessage(message)
                    setCancelable(false)
                    setCanceledOnTouchOutside(false)
                    show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 显示带标题的加载对话框
     * @param activity 上下文Activity
     * @param title 标题
     * @param message 提示消息
     */
    fun show(activity: Activity, title: String, message: String) {
        dismiss() // 先关闭已有的dialog
        if (!activity.isDestroyed && !activity.isFinishing) {
            try {
                loadingDialog = ProgressDialog(activity).apply {
                    setTitle(title)
                    setMessage(message)
                    setCancelable(false)
                    setCanceledOnTouchOutside(false)
                    show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 关闭加载对话框
     */
    fun dismiss() {
        loadingDialog?.takeIf { it.isShowing }?.dismiss()
        loadingDialog = null
    }
}