package com.pengxh.kt.lite.widget.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import com.pengxh.kt.lite.R
import com.pengxh.kt.lite.databinding.DialogNoNetworkBinding
import com.pengxh.kt.lite.extensions.binding
import com.pengxh.kt.lite.extensions.resetParams

class NoNetworkDialog private constructor(builder: Builder) : Dialog(builder.context) {
    private val listener: OnDialogButtonClickListener = builder.listener

    class Builder {
        lateinit var context: Context
        lateinit var listener: OnDialogButtonClickListener

        fun setContext(context: Context): Builder {
            this.context = context
            return this
        }

        fun setOnDialogButtonClickListener(listener: OnDialogButtonClickListener): Builder {
            this.listener = listener
            return this
        }

        fun build(): NoNetworkDialog {
            return NoNetworkDialog(this)
        }
    }

    private val binding: DialogNoNetworkBinding by binding()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.resetParams(Gravity.CENTER, R.style.UserDefinedAnimation, 0.85f)
        setCancelable(false)
        setCanceledOnTouchOutside(false)

        binding.dismissView.setOnClickListener { dismiss() }

        binding.dialogButton.setOnClickListener {
            listener.onButtonClick()
            dismiss()
        }
    }

    interface OnDialogButtonClickListener {
        fun onButtonClick()
    }
}