package com.pengxh.daily.app.widgets

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.DialogTaskMessageBinding
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.kt.lite.adapter.NormalRecyclerAdapter
import com.pengxh.kt.lite.adapter.ViewHolder
import com.pengxh.kt.lite.divider.RecyclerViewItemOffsets
import com.pengxh.kt.lite.extensions.binding
import com.pengxh.kt.lite.extensions.initDialogLayoutParams
import com.pengxh.kt.lite.extensions.toJson

class TaskMessageDialog private constructor(builder: Builder) : Dialog(builder.context) {
    private val tasks = builder.tasks
    private val listener = builder.listener

    class Builder {
        lateinit var context: Context
        lateinit var tasks: MutableList<DailyTaskBean>
        lateinit var listener: OnDialogButtonClickListener

        fun setContext(context: Context): Builder {
            this.context = context
            return this
        }

        fun setTasks(tasks: MutableList<DailyTaskBean>): Builder {
            this.tasks = tasks
            return this
        }

        fun setOnDialogButtonClickListener(listener: OnDialogButtonClickListener): Builder {
            this.listener = listener
            return this
        }

        fun build(): TaskMessageDialog {
            return TaskMessageDialog(this)
        }
    }

    private val binding: DialogTaskMessageBinding by binding()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.initDialogLayoutParams(0.8f)
        setCanceledOnTouchOutside(false)

        //数据绑定
        binding.recyclerView.addItemDecoration(RecyclerViewItemOffsets(5, 5, 5, 5))
        binding.recyclerView.adapter = object : NormalRecyclerAdapter<DailyTaskBean>(
            R.layout.item_task_rv_g, tasks
        ) {
            override fun convertView(
                viewHolder: ViewHolder, position: Int, item: DailyTaskBean
            ) {
                viewHolder.setText(R.id.taskTimeView, item.time)
            }
        }

        binding.confirmButton.setOnClickListener {
            listener.onConfirmClick(tasks.toJson())
            dismiss()
        }
    }

    interface OnDialogButtonClickListener {
        fun onConfirmClick(taskValue: String)
    }
}