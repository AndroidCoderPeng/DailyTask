package com.pengxh.kt.lite.widget.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import com.pengxh.kt.lite.databinding.DialogChangePwdBinding
import com.pengxh.kt.lite.extensions.binding
import com.pengxh.kt.lite.extensions.initDialogLayoutParams
import com.pengxh.kt.lite.extensions.isLetterAndDigit
import com.pengxh.kt.lite.extensions.show

class ChangePasswordDialog private constructor(builder: Builder) : Dialog(builder.context) {
    private val ctx: Context = builder.context
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

        fun build(): ChangePasswordDialog {
            return ChangePasswordDialog(this)
        }
    }

    private val binding: DialogChangePwdBinding by binding()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.initDialogLayoutParams(0.85f)
        setCancelable(true)
        setCanceledOnTouchOutside(true)

        binding.confirmButton.setOnClickListener(View.OnClickListener {
            val oldPwd = binding.oldPwdView.text.toString()
            val newPwd = binding.newPwdView.text.toString()
            val confirmPwd = binding.confirmPwdView.text.toString()
            if (oldPwd.isBlank()) {
                "请输入原密码".show(ctx)
                return@OnClickListener
            }
            if (newPwd.isBlank()) {
                "请输入新密码".show(ctx)
                return@OnClickListener
            }
            if (confirmPwd.isBlank()) {
                "请再次确认密码".show(ctx)
                return@OnClickListener
            }
            if (!newPwd.isLetterAndDigit()) {
                "新密码需包含数字和字母".show(ctx)
                return@OnClickListener
            }
            if (newPwd != confirmPwd) {
                "新密码和确认密码不一致，请检查".show(ctx)
                return@OnClickListener
            }
            listener.onConfirmClick(oldPwd, newPwd)
            dismiss()
        })
    }

    interface OnDialogButtonClickListener {
        fun onConfirmClick(oldPwd: String, newPwd: String)
    }
}