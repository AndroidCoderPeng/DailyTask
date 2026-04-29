package com.pengxh.daily.app.ui

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.LogFileManager
import org.greenrobot.eventbus.EventBus

class KeyguardDismissActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private var dismissAttempts = 0
    private var hasWindowFocus = false
    private var resumed = false
    private var dismissRequestScheduled = false

    private val dismissRequestRunnable = Runnable {
        dismissRequestScheduled = false
        requestDismissKeyguardIfPossible()
    }

    private val timeoutRunnable = Runnable {
        finishWithResult(false, "锁屏唤醒请求超时")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverKeyguard()
        setContentView(buildContentView())
        handler.postDelayed(timeoutRunnable, DISMISS_TIMEOUT_MS)
    }

    private fun showOverKeyguard() {
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun buildContentView(): LinearLayout {
        val padding = (48 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)

            addView(TextView(this@KeyguardDismissActivity).apply {
                text = "正在尝试解除滑动锁屏"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 20f
                gravity = Gravity.CENTER
            })
            addView(TextView(this@KeyguardDismissActivity).apply {
                text = "如果设备设置了密码、图案或指纹，本次任务会停止并发送告警"
                setTextColor(0xB3FFFFFF.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 0)
            })
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        scheduleDismissRequest(DISMISS_REQUEST_DELAY_MS)
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        hasWindowFocus = hasFocus
        if (hasFocus) {
            scheduleDismissRequest(0L)
        }
    }

    private fun scheduleDismissRequest(delayMs: Long) {
        if (finished) return
        if (dismissRequestScheduled) return
        dismissRequestScheduled = true
        handler.postDelayed(dismissRequestRunnable, delayMs)
    }

    private fun requestDismissKeyguardIfPossible() {
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        if (keyguardManager == null || !keyguardManager.isKeyguardLocked) {
            finishWithResult(true, "锁屏已解除，准备继续执行任务")
            return
        }
        if (keyguardManager.isDeviceSecure || keyguardManager.isKeyguardSecure) {
            finishWithResult(false, "检测到安全锁屏，需要人工解锁")
            return
        }
        if (!resumed || !hasWindowFocus) {
            scheduleDismissRequest(DISMISS_REQUEST_DELAY_MS)
            return
        }
        if (dismissAttempts >= MAX_DISMISS_ATTEMPTS) {
            finishWithResult(false, "滑动锁屏解除失败，重试后设备仍处于锁屏状态")
            return
        }
        dismissAttempts++
        LogFileManager.writeLog("尝试解除滑动锁屏，第${dismissAttempts}次")
        keyguardManager.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    finishAfterVerifyingDismissed("滑动锁屏解除成功，准备继续执行任务")
                }

                override fun onDismissCancelled() {
                    retryOrFinish("滑动锁屏解除被取消")
                }

                override fun onDismissError() {
                    retryOrFinish("滑动锁屏解除失败")
                }
            }
        )
        handler.postDelayed({
            if (finished) return@postDelayed
            if (!keyguardManager.isKeyguardLocked) {
                finishWithResult(true, "滑动锁屏已解除，准备继续执行任务")
            } else if (dismissAttempts < MAX_DISMISS_ATTEMPTS) {
                scheduleDismissRequest(DISMISS_RETRY_DELAY_MS)
            }
        }, DISMISS_VERIFY_DELAY_MS)
    }

    private fun finishAfterVerifyingDismissed(successMessage: String) {
        handler.postDelayed({
            val keyguardManager = getSystemService(KeyguardManager::class.java)
            if (keyguardManager == null || !keyguardManager.isKeyguardLocked) {
                finishWithResult(true, successMessage)
            } else {
                retryOrFinish("系统返回解锁成功，但设备仍处于锁屏状态")
            }
        }, DISMISS_VERIFY_DELAY_MS)
    }

    private fun retryOrFinish(message: String) {
        if (finished) return
        LogFileManager.writeLog(message)
        if (dismissAttempts < MAX_DISMISS_ATTEMPTS) {
            scheduleDismissRequest(DISMISS_RETRY_DELAY_MS)
        } else {
            finishWithResult(false, message)
        }
    }

    private fun finishWithResult(success: Boolean, message: String) {
        if (finished) return
        finished = true
        handler.removeCallbacks(timeoutRunnable)
        LogFileManager.writeLog(message)
        EventBus.getDefault().post(ApplicationEvent.KeyguardDismissFinished(success, message))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    companion object {
        private const val DISMISS_REQUEST_DELAY_MS = 250L
        private const val DISMISS_RETRY_DELAY_MS = 800L
        private const val DISMISS_VERIFY_DELAY_MS = 500L
        private const val DISMISS_TIMEOUT_MS = 4500L
        private const val MAX_DISMISS_ATTEMPTS = 2
    }
}
