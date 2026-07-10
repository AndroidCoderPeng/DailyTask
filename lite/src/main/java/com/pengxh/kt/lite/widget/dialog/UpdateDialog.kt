package com.pengxh.kt.lite.widget.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import com.pengxh.kt.lite.R
import com.pengxh.kt.lite.adapter.NormalRecyclerAdapter
import com.pengxh.kt.lite.adapter.ViewHolder
import com.pengxh.kt.lite.databinding.DialogUpdateBinding
import com.pengxh.kt.lite.extensions.binding
import com.pengxh.kt.lite.extensions.initDialogLayoutParams

class UpdateDialog(builder: Builder) : Dialog(builder.context) {

    private val kTag = "UpdateDialog"
    private val message = builder.message
    private val listener = builder.listener

    class Builder {
        lateinit var context: Context
        lateinit var message: ArrayList<String>
        lateinit var listener: OnUpdateListener

        fun setContext(context: Context): Builder {
            this.context = context
            return this
        }

        fun setUpdateMessage(message: ArrayList<String>): Builder {
            this.message = message
            return this
        }

        fun setOnUpdateListener(listener: OnUpdateListener): Builder {
            this.listener = listener
            return this
        }

        fun build(): UpdateDialog {
            return UpdateDialog(this)
        }
    }

    private val binding: DialogUpdateBinding by binding()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.initDialogLayoutParams(1f)
        setCanceledOnTouchOutside(false)
        setCancelable(false)

        binding.recyclerView.adapter = object :
            NormalRecyclerAdapter<String>(R.layout.item_update_rv_l, message) {
            override fun convertView(viewHolder: ViewHolder, position: Int, item: String) {
                viewHolder.setText(R.id.indexView, "${position + 1}.")
                    .setText(R.id.textView, item)
            }
        }

        binding.updateVersionButton.setOnClickListener {
            listener.onUpdate()
            dismiss()
        }

        binding.cancelVersionButton.setOnClickListener { dismiss() }
    }

    interface OnUpdateListener {
        fun onUpdate()
    }
}