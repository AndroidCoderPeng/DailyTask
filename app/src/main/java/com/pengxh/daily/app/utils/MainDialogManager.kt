package com.pengxh.daily.app.utils

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 主页面弹窗集合
 *
 * 弹窗只负责"收集用户输入"，
 * 拿到结果后回调给调用方，具体的业务动作由调用方决定。
 */
object MainDialogManager {

    // TODO: 2024/6/10 需要重写
    fun showImportInput(context: Context, onConfirm: (String) -> Unit) {
//        AlertInputDialog.Builder()
//            .setContext(context)
//            .setTitle("导入任务")
//            .setHintMessage("请将导出的任务粘贴到这里")
//            .setNegativeButton("取消")
//            .setPositiveButton("确定")
//            .setOnDialogButtonClickListener(object :
//                AlertInputDialog.OnDialogButtonClickListener {
//                override fun onConfirmClick(value: String) {
//                    onConfirm(value)
//                }
//
//                override fun onCancelClick() {}
//            }).build().show()
    }

    fun showImportResult(context: Context, warnings: List<String>) {
        MaterialAlertDialogBuilder(context)
            .setTitle("导入完成")
            .setMessage("任务导入成功\n\n${warnings.joinToString("\n")}")
            .setPositiveButton("知道了", null)
            .show()
    }
}