package com.pengxh.kt.lite.widget

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.pengxh.kt.lite.R

class KeyBoardView(context: Context, attrs: AttributeSet) :
    LinearLayout(context, attrs), View.OnClickListener {

    private var listener: KeyboardClickListener? = null

    init {
        inflate(context, R.layout.widget_view_keyboard, this)
        setChildViewOnclick(this)
    }

    /**
     * 设置键盘子View的点击事件
     */
    private fun setChildViewOnclick(parent: ViewGroup) {
        val childCount: Int = parent.childCount
        for (i in 0 until childCount) {
            val view: View = parent.getChildAt(i)
            if (view is ViewGroup) {
                setChildViewOnclick(view)
                continue
            }
            view.setOnClickListener(this)
        }
    }

    fun dispatchKeyEventInFullScreen(event: KeyEvent?): Boolean {
        if (event == null) {
            return false
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (isShown) {
                this.visibility = GONE
                return true
            }
        }
        return false
    }

    override fun onClick(v: View) {
        val value = (v as Button).text.toString()
        if (value == "DEL") {
            listener?.onDelete()
        } else {
            listener?.onClick(value)
        }
    }

    fun setKeyboardClickListener(keyboardClickListener: KeyboardClickListener?) {
        listener = keyboardClickListener
    }

    interface KeyboardClickListener {
        fun onClick(value: String)

        fun onDelete()
    }
}