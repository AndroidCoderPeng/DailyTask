package com.pengxh.kt.lite.utils.state

import com.pengxh.kt.lite.enums.HttpRequestState

/**
 * 状态回调内联函数
 * */
inline fun httpRequestCallback(
    crossinline onLoading: () -> Unit = {},
    crossinline onSuccess: () -> Unit = {},
    crossinline onFailed: (String) -> Unit = {}
): HttpRequestCallback = object : HttpRequestCallback {
    override fun onStateChanged(state: HttpRequestState, message: String) {
        when (state) {
            HttpRequestState.LOADING -> onLoading()
            HttpRequestState.SUCCESS -> onSuccess()
            HttpRequestState.ERROR -> onFailed(message)
        }
    }
}