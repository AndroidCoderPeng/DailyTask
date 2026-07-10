package com.pengxh.kt.lite.widget.dialog

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import com.pengxh.kt.lite.databinding.DialogGlobeAlertBinding
import com.pengxh.kt.lite.extensions.bindView
import com.pengxh.kt.lite.extensions.getScreenWidth


class GlobeAlertDialog(private val listener: OnDialogButtonClickListener) : DialogFragment() {

    private val binding by bindView<DialogGlobeAlertBinding>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val window = dialog?.window
        window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window?.decorView?.setBackgroundColor(Color.TRANSPARENT)
        val params = window?.attributes
        params?.width = ((context?.getScreenWidth()!! * 0.8f).toInt())
        params?.height = WindowManager.LayoutParams.WRAP_CONTENT
        window?.attributes = params

        binding.dialogCancelButton.setOnClickListener {
            listener.onCancelClick()
        }

        binding.dialogConfirmButton.setOnClickListener {
            listener.onConfirmClick()
        }
        return binding.root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    interface OnDialogButtonClickListener {
        fun onConfirmClick()

        fun onCancelClick()
    }
}