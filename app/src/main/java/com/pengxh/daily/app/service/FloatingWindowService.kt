package com.pengxh.daily.app.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.utils.BroadcastManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.MessageType
import com.pengxh.kt.lite.utils.SaveKeyValues

class FloatingWindowService : Service() {
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val actions by lazy {
        listOf(
            MessageType.SHOW_FLOATING_WINDOW.action,
            MessageType.HIDE_FLOATING_WINDOW.action,
            MessageType.SET_DING_DING_OVERTIME.action,
            MessageType.UPDATE_FLOATING_WINDOW_TIME.action
        )
    }
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private val floatView by lazy {
        val tempContainer = LinearLayout(this) // 创建一个临时的父布局
        LayoutInflater.from(this).inflate(R.layout.window_floating, tempContainer)
    }
    private val textView by lazy { floatView.findViewById<TextView>(R.id.timeView) }
    private val broadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.action?.let {
                    when (MessageType.fromAction(it)) {
                        MessageType.SHOW_FLOATING_WINDOW -> {
                            floatView.alpha = 1.0f
                            if (textView.text == "0s") {
                                val time = SaveKeyValues.getValue(
                                    Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
                                ) as Int
                                textView.text = "${time}s"
                            }
                        }

                        MessageType.HIDE_FLOATING_WINDOW -> floatView.alpha = 0.0f

                        MessageType.SET_DING_DING_OVERTIME -> {
                            // 更新钉钉任务超时时间
                            val time = intent.getIntExtra("time", 30)
                            textView.text = "${time}s"
                        }

                        MessageType.UPDATE_FLOATING_WINDOW_TIME -> {
                            // 更新悬浮窗倒计时
                            val tick = intent.getLongExtra("tick", 30)
                            textView.text = "${tick}s"
                            if (tick <= 0) {
                                floatView.alpha = 0.0f
                            } else {
                                floatView.alpha = 1.0f
                            }
                        }

                        else -> {}
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressWarnings("all")
    override fun onCreate() {
        super.onCreate()
        BroadcastManager.getDefault().registerReceivers(this, actions, broadcastReceiver)

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

    override fun onDestroy() {
        super.onDestroy()
        actions.forEach {
            BroadcastManager.getDefault().unregisterReceiver(this, it)
        }
        windowManager.removeViewImmediate(floatView)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}