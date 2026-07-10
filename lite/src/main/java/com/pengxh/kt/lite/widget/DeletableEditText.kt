package com.pengxh.kt.lite.widget

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.animation.AnimationUtils
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import androidx.core.view.doOnNextLayout
import com.pengxh.kt.lite.R

class DeletableEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr), OnFocusChangeListener, TextWatcher {

    private val kTag = "DeletableEditText"

    /**
     * 删除按钮的引用
     */
    private var clearDrawable = compoundDrawablesRelative[2]
    private var hasFocus = false

    init {
        /**
         * 获取EditText的DrawableRight
         * 2是获得右边的图片，顺序是左上右下（0,1,2,3,）
         * */
        if (clearDrawable == null) {
            clearDrawable = ContextCompat.getDrawable(context, R.drawable.ic_edit_text_delete)
        }

        setClearIconVisible(false)
        onFocusChangeListener = this
        addTextChangedListener(this)

        doOnNextLayout {
            val iconSize = (textSize * 1.2).toInt()
            Log.d(kTag, "Clear IconSize: $iconSize")
            clearDrawable.setBounds(0, 0, iconSize, iconSize)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val drawable = compoundDrawablesRelative[2]
            if (drawable != null) {
                val drawableStart = width - totalPaddingRight
                val drawableEnd = width - paddingRight
                if (event.x.toInt() in drawableStart..drawableEnd) {
                    removeTextChangedListener(this)
                    setText("")
                    addTextChangedListener(this)
                    performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

    /**
     * 当输入框里面内容发生变化的时候回调的方法
     */
    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        if (hasFocus) {
            setClearIconVisible(s.isNotEmpty())
        }
    }

    override fun afterTextChanged(s: Editable) {}

    /**
     * 当EditText焦点发生变化的时候，判断里面字符串长度设置清除图标的显示与隐藏
     */
    override fun onFocusChange(v: View, hasFocus: Boolean) {
        this.hasFocus = hasFocus
        setClearIconVisible(hasFocus && text.toString().isNotEmpty())
    }

    private fun setClearIconVisible(visible: Boolean) {
        val rightIcon = if (visible) clearDrawable else null
        setCompoundDrawablesRelative(
            compoundDrawablesRelative[0],
            compoundDrawablesRelative[1],
            rightIcon,
            compoundDrawablesRelative[3]
        )
    }

    fun shakeIfEmpty() {
        if (text.isNullOrEmpty()) {
            val animation = AnimationUtils.loadAnimation(context, R.anim.shake_animation)
            startAnimation(animation)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeTextChangedListener(this)
    }
}