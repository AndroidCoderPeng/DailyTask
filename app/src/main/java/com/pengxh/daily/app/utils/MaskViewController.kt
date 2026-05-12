package com.pengxh.daily.app.utils

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.pengxh.daily.app.databinding.ActivityMainBinding
import com.pengxh.kt.lite.extensions.setScreenBrightness
import org.greenrobot.eventbus.EventBus
import java.util.Random

class MaskViewController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val insetsController: WindowInsetsControllerCompat
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentAnimation: Animation? = null
    private val random = Random()
    private var clockAnimationRunnable: Runnable? = null

    fun showMaskView() {
        // 隐藏悬浮窗
        EventBus.getDefault().post(ApplicationEvent.HideFloatingWindow)

        // 隐藏系统栏
        insetsController.apply {
            hide(WindowInsetsCompat.Type.statusBars())
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // 显示蒙层
        binding.maskView.visibility = View.VISIBLE
        currentAnimation?.cancel()
        currentAnimation = ScaleAnimation(1.0f, 1.0f, 0.0f, 1.0f).apply {
            duration = 500
        }
        binding.maskView.startAnimation(currentAnimation)

        // 关闭屏幕亮度
        activity.window.setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF)

        // 隐藏任务界面
        binding.rootView.visibility = View.GONE

        // 启动时钟动画
        startClockAnimation()
    }

    fun hideMaskView() {
        // 显示悬浮窗
        EventBus.getDefault().post(ApplicationEvent.ShowFloatingWindow)

        // 停止时钟动画
        stopClockAnimation()

        // 恢复系统栏
        insetsController.apply {
            show(WindowInsetsCompat.Type.statusBars())
            show(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }

        // 隐藏蒙层
        currentAnimation?.cancel()
        currentAnimation = ScaleAnimation(1.0f, 1.0f, 1.0f, 0.0f).apply {
            duration = 500
        }
        binding.maskView.startAnimation(currentAnimation)

        // 恢复屏幕亮度
        activity.window.setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)

        binding.maskView.visibility = View.GONE
        binding.rootView.visibility = View.VISIBLE
    }

    fun isMaskVisible(): Boolean = binding.maskView.isVisible

    private fun startClockAnimation() {
        clockAnimationRunnable = object : Runnable {
            override fun run() {
                if (binding.maskView.width == 0 || binding.maskView.height == 0) return

                binding.clockView.measure(
                    View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED
                )
                val clockWidth = binding.clockView.measuredWidth
                val clockHeight = binding.clockView.measuredHeight

                val maxX = binding.maskView.width - clockWidth
                val maxY = binding.maskView.height - clockHeight

                if (maxX > 0 && maxY > 0) {
                    val newX = random.nextInt(maxX.coerceAtLeast(1))
                    val newY = random.nextInt(maxY.coerceAtLeast(1))

                    binding.clockView.animate()
                        .x(newX.toFloat())
                        .y(newY.toFloat())
                        .setDuration(1000)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                }

                mainHandler.postDelayed(this, 30000)
            }
        }
        mainHandler.postDelayed(clockAnimationRunnable!!, 30000)
    }

    private fun stopClockAnimation() {
        clockAnimationRunnable?.let {
            mainHandler.removeCallbacks(it)
            clockAnimationRunnable = null
        }
    }

    fun destroy() {
        stopClockAnimation()
        currentAnimation?.cancel()
        currentAnimation = null
    }
}