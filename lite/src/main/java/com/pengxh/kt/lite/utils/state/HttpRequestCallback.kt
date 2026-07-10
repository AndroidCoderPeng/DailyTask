package com.pengxh.kt.lite.utils.state

import com.pengxh.kt.lite.enums.HttpRequestState

interface HttpRequestCallback {
    fun onStateChanged(state: HttpRequestState, message: String = "")
}