package com.pengxh.daily.app.widget

import android.content.Context
import android.view.LayoutInflater
import com.github.gzuliyujiang.wheelpicker.widget.TimeWheelLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.extensions.toTimeEntity
import java.util.Locale

/**
 * 任务时间选择弹窗
 *
 * 新增任务、修改任务时间共用同一套 UI 与取值逻辑，
 * 避免两处重复的 inflate + TimeWheelLayout 取值代码。
 */
object TaskTimeDialog {

    /**
     * @param context 上下文
     * @param title 弹窗标题
     * @param initialTime 初始选中时间（"HH:mm:ss"），为空则不预选
     * @param onConfirm 确认回调，回调参数为格式化后的 "HH:mm:ss"
     */
    fun show(
        context: Context,
        title: String,
        initialTime: String? = null,
        onConfirm: (String) -> Unit
    ) {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.bottom_sheet_layout_select_time, null)
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(view)

        view.findViewById<MaterialTextView>(R.id.titleView).text = title

        val timePicker = view.findViewById<TimeWheelLayout>(R.id.timePicker)
        initialTime?.let { timePicker.setDefaultValue(it.toTimeEntity()) }

        view.findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            val time = String.Companion.format(
                Locale.getDefault(), "%02d:%02d:%02d",
                timePicker.selectedHour,
                timePicker.selectedMinute,
                timePicker.selectedSecond
            )
            onConfirm(time)
            dialog.dismiss()
        }

        dialog.show()
    }
}