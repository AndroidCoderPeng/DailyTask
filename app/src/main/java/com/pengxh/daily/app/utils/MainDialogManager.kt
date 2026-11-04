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

    fun showUsageNotice(context: Context, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle("使用须知")
            .setMessage("本软件完全免费！仅供内部使用！严禁商用或者用作其他非法用途！\r\n近期发现有人在咸鱼私自倒卖本软件，请勿购买！如有购买，请联系卖家退款！")
            .setCancelable(false)
            .setPositiveButton("知道了") { _, _ -> onConfirm() }
            .show()
    }
}