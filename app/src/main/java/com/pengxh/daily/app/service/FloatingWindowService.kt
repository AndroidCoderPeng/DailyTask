package com.pengxh.daily.app.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.event.UpdateDingDingTimeoutEvent
import com.pengxh.daily.app.event.UpdateFloatingWindowTimeEvent
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.utils.SaveKeyValues
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe

class FloatingWindowService : Service() {
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private val floatView by lazy {
        val tempContainer = LinearLayout(this) // 创建一个临时的父布局
        LayoutInflater.from(this).inflate(R.layout.window_floating, tempContainer)
    }
    private val textView by lazy { floatView.findViewById<TextView>(R.id.timeView) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val broadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    handleIntent(intent)
                } else {
                    mainHandler.post {
                        handleIntent(intent)
                    }
                }
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Constant.BROADCAST_SHOW_FLOATING_WINDOW_ACTION -> {
                floatView.alpha = 1.0f
                val time = SaveKeyValues.getValue(
                    Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
                ) as Int
                textView.text = "$time"
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        EventBus.getDefault().register(this)

        initBroadcastReceiver()

        val floatLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).also {
            windowManager.addView(floatView, it)
        }
        val time = SaveKeyValues.getValue(
            Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
        ) as Int
        textView.text = "${time}s"
        try {
            floatView.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        floatLayoutParams.run {
                            initialX = x
                            initialY = y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        floatLayoutParams.run {
                            x = initialX + (event.rawX - initialTouchX).toInt()
                            y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(floatView, this)
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        initialX = 0
                        initialY = 0
                        initialTouchX = 0f
                        initialTouchY = 0f
                    }
                }
                true
            }
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: WindowManager.BadTokenException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Subscribe
    fun updateDingDingTimeout(event: UpdateDingDingTimeoutEvent) {
        // 更新钉钉任务超时时间
        textView.text = "${event.time}s"
    }

    @Subscribe
    fun updateFloatingWindowTime(event: UpdateFloatingWindowTimeEvent) {
        // 更新悬浮窗倒计时
        textView.text = "${event.seconds}s"
    }

    @Subscribe
    fun hideFloatingWindowTime(event: Any) {
        // 隐藏悬浮窗
        floatView.alpha = 0.0f
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun initBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(Constant.BROADCAST_SHOW_FLOATING_WINDOW_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
        unregisterReceiver(broadcastReceiver)
        windowManager.removeViewImmediate(floatView)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}