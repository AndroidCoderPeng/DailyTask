package com.pengxh.kt.lite.extensions

import android.app.Dialog
import android.graphics.Color
import android.view.WindowManager
import androidx.annotation.StyleRes
import androidx.core.graphics.drawable.toDrawable
import androidx.viewbinding.ViewBinding

fun Dialog.initDialogLayoutParams(ratio: Float) {
    val window = window ?: return
    window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
    window.decorView.setBackgroundColor(Color.TRANSPARENT)
    val params = window.attributes
    var r = ratio
    if (r >= 1) {
        r = 1f
    }
    params.width = ((context.getScreenWidth() * r).toInt())
    params.height = WindowManager.LayoutParams.WRAP_CONTENT
    window.attributes = params
}

fun Dialog.resetParams(gravity: Int, @StyleRes resId: Int = 0, ratio: Float) {
    val window = window ?: return
    window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
    window.decorView.setBackgroundColor(Color.TRANSPARENT)
    window.setGravity(gravity)
    if (resId != 0) {
        window.setWindowAnimations(resId)
    }
    val params = window.attributes
    var r = ratio
    if (r >= 1) {
        r = 1f
    }
    params.width = ((context.getScreenWidth() * r).toInt())
    params.height = WindowManager.LayoutParams.WRAP_CONTENT
    window.attributes = params
}

inline fun <reified VB : ViewBinding> Dialog.binding() = lazy(LazyThreadSafetyMode.NONE) {
    inflateBinding<VB>(layoutInflater).also { setContentView(it.root) }
}