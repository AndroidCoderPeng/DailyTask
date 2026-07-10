package com.pengxh.kt.lite.widget.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import com.pengxh.kt.lite.databinding.DialogProgressBinding
import com.pengxh.kt.lite.extensions.binding
import com.pengxh.kt.lite.extensions.initDialogLayoutParams

class ProgressDialog(context: Context) : Dialog(context) {

    private val binding: DialogProgressBinding by binding()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.initDialogLayoutParams(0.5f)
        setCanceledOnTouchOutside(false)
        setCancelable(false)
        binding.progressBar.progress = 0
        binding.progressText.text = "0 %"
    }

    fun setMaxProgress(max: Long) {
        binding.progressBar.max = max.toInt()
    }

    private fun getMaxProgress() = binding.progressBar.max

    fun updateProgress(progress: Int) {
        binding.progressBar.progress = progress

        val percent = (progress / getMaxProgress()) * 100
        binding.progressText.text = String.format("%.2f %%", percent)
    }
}