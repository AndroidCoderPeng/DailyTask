package com.pengxh.daily.app.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 悬浮窗控制器
 */
object FloatingWindowController {

    private val _timeTick = MutableSharedFlow<Int>(extraBufferCapacity = 2)
    val timeTick = _timeTick.asSharedFlow()

    private val _waitTime = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val waitTime = _waitTime.asSharedFlow()

    private val _visibility = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 1)
    val visibility = _visibility.asSharedFlow()

    fun updateTime(tick: Int) {
        _timeTick.tryEmit(tick)
    }

    fun setWaitTime(waitTime: Int) {
        _waitTime.tryEmit(waitTime)
    }

    fun show() {
        _visibility.tryEmit(true)
    }

    fun hide() {
        _visibility.tryEmit(false)
    }
}
