package com.pengxh.kt.lite.widget.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import com.pengxh.kt.lite.databinding.DialogMessageBinding
import com.pengxh.kt.lite.extensions.binding
import com.pengxh.kt.lite.extensions.initDialogLayoutParams

/**
 * 普通提示对话框对话框
 */
class AlertMessageDialog private constructor(builder: Builder) : Dialog(builder.context) {
    private val title = builder.title
    private val message = builder.message
    private val positiveBtn = builder.positiveBtn
    private val listener = builder.listener

    class Builder {
        lateinit var context: Context
        lateinit var title: String
        lateinit var message: String
        lateinit var positiveBtn: String
        lateinit var listener: OnDialogButtonClickListener

        fun setContext(context: Context): Builder {
            this.context = context
            return this
        }

        fun setTitle(title: String): Builder {
            this.title = title
            return this
        }

        fun setMessage(message: String): Builder {
            this.message = message
            return this
        }

        fun setPositiveButton(name: String): Builder {
            this.positiveBtn = name
            return this
        }

        fun setOnDialogButtonClickListener(listener: OnDialogButtonClickListener): Builder {
            this.listener = listener
            return this
        }

        fun build(): AlertMessageDialog {
            return AlertMessageDialog(this)
        }
    }

    private val binding: DialogMessageBinding by binding()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.initDialogLayoutParams(0.8f)
        setCanceledOnTouchOutside(false)
        initView()
    }

    private fun initView() {
        if (title.isNotBlank()) {
            binding.dialogTitleView.text = title
        }
        if (message.isNotBlank()) {
            binding.dialogMessageView.text = message
        }
        if (!TextUtils.isEmpty(positiveBtn)) {
            binding.dialogConfirmButton.text = positiveBtn
        }
        binding.dialogConfirmButton.setOnClickListener {
            listener.onConfirmClick()
            dismiss()
        }
    }

    interface OnDialogButtonClickListener {
        fun onConfirmClick()
    }
}