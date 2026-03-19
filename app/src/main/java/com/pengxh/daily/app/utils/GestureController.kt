package com.pengxh.daily.app.utils

import android.content.Context
import android.os.Handler
import android.view.GestureDetector
import android.view.MotionEvent
import com.pengxh.kt.lite.utils.SaveKeyValues

/**
 * 手势控制器
 *
 * 职责：
 * 1. 管理滑动手势检测
 * 2. 根据手势操作控制蒙层显示/隐藏
 * 3. 提供手势开关配置
 *
 * @param context 上下文
 * @param maskViewController 蒙层视图控制器
 * @param mainHandler 主线程Handler
 */
class GestureController(
    private val context: Context,
    private val maskViewController: MaskViewController,
    private val mainHandler: Handler
) {

    private val minFlingDistance = 1000f

    private val gestureDetector: GestureDetector

    init {
        gestureDetector = GestureDetector(context, GestureListener())
    }

    /**
     * 处理触摸事件
     *
     * @param event 触摸事件
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    /**
     * 手势监听器
     */
    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
        ): Boolean {
            val isGestureEnabled =
                SaveKeyValues.getValue(Constant.GESTURE_DETECTOR_KEY, false) as Boolean
            // 如果手势未启用，则不处理
            if (!isGestureEnabled) {
                return false
            }

            // 计算垂直滑动距离
            val deltaY = calculateDeltaY(e1, e2)

            // 处理从上向下滑动手势（显示蒙层）
            if (isSwipeDown(deltaY, e1, e2)) {
                handleShowMask()
                return true
            }

            // 处理从下向上滑动手势（隐藏蒙层）
            if (isSwipeUp(deltaY, e1, e2)) {
                handleHideMask()
                return true
            }

            return super.onFling(e1, e2, velocityX, velocityY)
        }
    }

    /**
     * 计算垂直滑动距离
     */
    private fun calculateDeltaY(e1: MotionEvent?, e2: MotionEvent): Float {
        return kotlin.math.abs(e2.y - (e1?.y ?: e2.y))
    }

    /**
     * 判断是否为向下滑动手势
     */
    private fun isSwipeDown(deltaY: Float, e1: MotionEvent?, e2: MotionEvent): Boolean {
        return deltaY > minFlingDistance
                && (e2.y - (e1?.y ?: e2.y)) > 0
                && !maskViewController.isMaskVisible()
    }

    /**
     * 判断是否为向上滑动手势
     */
    private fun isSwipeUp(deltaY: Float, e1: MotionEvent?, e2: MotionEvent): Boolean {
        return deltaY > minFlingDistance
                && (e2.y - (e1?.y ?: e2.y)) < 0
                && maskViewController.isMaskVisible()
    }

    /**
     * 处理显示蒙层
     */
    private fun handleShowMask() {
        if (!maskViewController.isMaskVisible()) {
            maskViewController.showMaskView(mainHandler)
        }
    }

    /**
     * 处理隐藏蒙层
     */
    private fun handleHideMask() {
        if (maskViewController.isMaskVisible()) {
            maskViewController.hideMaskView(mainHandler)
        }
    }
}