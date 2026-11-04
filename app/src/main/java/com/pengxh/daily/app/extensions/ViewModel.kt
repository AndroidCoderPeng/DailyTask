package com.pengxh.daily.app.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * ViewModel扩展方法：启动协程
 *
 * 注意：block 必须放在最后，否则调用方写 launch { } 时，
 * 尾随 lambda 会被绑定到 onComplete 上，block 反而拿不到值。
 *
 * @param onError 错误回调方法
 * @param onComplete 完成回调方法
 * @param block 协程逻辑
 */
fun ViewModel.launch(
    onError: (Throwable) -> Unit = {},
    onComplete: () -> Unit = {},
    block: suspend CoroutineScope.() -> Unit
) {
    viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError(e)
        } finally {
            onComplete()
        }
    }
}