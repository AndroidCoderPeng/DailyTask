package com.pengxh.daily.app.utils

/**
 * 悬浮窗控制器
 *
 * FloatingWindowService 在 onCreate/onDestroy 中注册/注销自身，
 * 其他组件直接调用方法即可。
 */
object FloatingWindowController {

    interface OnFloatingWindowView {
        fun updateTime(tick: Int)
        fun setOvertime(seconds: Int)
        fun setVisible(visible: Boolean)
    }

    @Volatile
    private var view: OnFloatingWindowView? = null

    fun register(v: OnFloatingWindowView) {
        view = v
    }

    fun unregister() {
        view = null
    }

    fun updateTime(tick: Int) {
        view?.updateTime(tick)
    }

    fun setOvertime(seconds: Int) {
        view?.setOvertime(seconds)
    }

    fun show() {
        view?.setVisible(true)
    }

    fun hide() {
        view?.setVisible(false)
    }
}
