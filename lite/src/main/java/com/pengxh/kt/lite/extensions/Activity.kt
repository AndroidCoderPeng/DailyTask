package com.pengxh.kt.lite.extensions

import androidx.appcompat.app.AppCompatActivity
import androidx.viewbinding.ViewBinding

inline fun <reified VB : ViewBinding> AppCompatActivity.binding() =
    lazy(LazyThreadSafetyMode.NONE) {
        inflateBinding<VB>(layoutInflater).also { setContentView(it.root) }
    }