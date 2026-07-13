package com.pengxh.daily.app.utils

/**
 * 投影截屏服务 → UI 事件
 * 通过 CaptureImageService.projectionEvents: SharedFlow 传递
 */
sealed class ProjectionEvent {
    data object Ready : ProjectionEvent()
    data object Failed : ProjectionEvent()
}