package com.pengxh.kt.lite.widget.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import com.pengxh.kt.lite.databinding.DialogInputBinding
import com.pengxh.kt.lite.extensions.binding
import com.pengxh.kt.lite.extensions.initDialogLayoutParams
import com.pengxh.kt.lite.extensions.show

/**
 * 输入对话框
 */
class AlertInputDialog private constructor(builder: Builder) : Dialog(builder.context) {
    private val ctx = builder.context
    private val title = builder.title
    private val hint = builder.hint
    private val positiveBtn = builder.positiveBtn
    private val negativeBtn = builder.negativeBtn
    private val listener = builder.listener

    class Builder {
        lateinit var context: Context
        lateinit var title: String
        lateinit var hint: String
        lateinit var positiveBtn: String
        lateinit var negativeBtn: String
        lateinit var listener: OnDialogButtonClickListener

        fun setContext(context: Context): Builder {
            this.context = context
            return this
        }

        fun setTitle(title: String): Builder {
            this.title = title
            return this
        }

        fun setHintMessage(hint: String): Builder {
            this.hint = hint
            return this
        }

        fun setPositiveButton(name: String): Builder {
            positiveBtn = name
            return this
        }

        fun setNegativeButton(name: String): Builder {
            negativeBtn = name
            return this
        }

        fun setOnDialogButtonClickListener(listener: OnDialogButtonClickListener): Builder {
            this.listener = listener
            return this
        }

        fun build(): AlertInputDialog {
            return AlertInputDialog(this)
        }
    }

    private val binding: DialogInputBinding by binding()

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
        if (hint.isNotBlank()) {
            binding.dialogInputView.hint = hint
        }
        if (negativeBtn.isNotBlank()) {
            binding.dialogCancelButton.text = negativeBtn
        }
        binding.dialogCancelButton.setOnClickListener {
            listener.onCancelClick()
            dismiss()
        }
        if (positiveBtn.isNotBlank()) {
            binding.dialogConfirmButton.text = positiveBtn
        }
        binding.dialogConfirmButton.setOnClickListener(View.OnClickListener {
            val inputValue = binding.dialogInputView.text.toString().trim()
            if (inputValue.isBlank()) {
                "输入错误，请检查！".show(ctx)
                return@OnClickListener
            }
            listener.onConfirmClick(inputValue)
            dismiss()
        })
    }

    interface OnDialogButtonClickListener {
        fun onConfirmClick(value: String)

        fun onCancelClick()
    }
}