package com.pengxh.daily.app.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.WidgetViewEmptyBinding

class EmptyView(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    private var binding = WidgetViewEmptyBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        val type = context.obtainStyledAttributes(attrs, R.styleable.EmptyView)
        val isShowReloadButton = type.getBoolean(R.styleable.EmptyView_show_reload_button, true)
        type.recycle()
        if (isShowReloadButton) {
            binding.reloadButton.visibility = VISIBLE
            binding.reloadButton.setOnClickListener {
                listener?.onReloadButtonClicked()
            }
        } else {
            binding.reloadButton.visibility = GONE
        }
    }

    private var listener: OnClickListener? = null

    fun setOnClickListener(listener: OnClickListener?) {
        this.listener = listener
    }

    interface OnClickListener {
        fun onReloadButtonClicked()
    }
}