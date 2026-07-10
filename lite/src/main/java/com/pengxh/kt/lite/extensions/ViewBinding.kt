package com.pengxh.kt.lite.extensions

import android.view.LayoutInflater
import androidx.viewbinding.ViewBinding

inline fun <reified VB : ViewBinding> inflateBinding(layoutInflater: LayoutInflater): VB {
    return VB::class.java
        .getMethod("inflate", LayoutInflater::class.java)
        .invoke(null, layoutInflater) as VB
}
