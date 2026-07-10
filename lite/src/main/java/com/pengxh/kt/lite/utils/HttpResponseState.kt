package com.pengxh.kt.lite.utils

/**
 * 网络请求状态密封类（用于关注返回值的情况）
 */
sealed class HttpResponseState<out T> {
    /**
     * 加载中
     */
    object Loading : HttpResponseState<Nothing>()

    /**
     * 成功
     */
    data class Success<T>(val body: T) : HttpResponseState<T>()

    /**
     * 失败
     */
    data class Error(
        val code: Int? = null,
        val message: String,
        val ex: Throwable? = null
    ) : HttpResponseState<Nothing>()
}