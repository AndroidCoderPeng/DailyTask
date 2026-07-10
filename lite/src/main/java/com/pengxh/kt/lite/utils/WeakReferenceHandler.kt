package com.pengxh.kt.lite.utils

import android.os.Handler
import android.os.Looper
import android.os.Message
import java.lang.ref.WeakReference

class WeakReferenceHandler(callback: Callback) : Handler(Looper.getMainLooper()) {

    private var weakReference = WeakReference(callback)

    override fun handleMessage(msg: Message) {
        weakReference.get()?.handleMessage(msg)
    }
}