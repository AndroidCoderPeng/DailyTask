package com.pengxh.daily.app.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.utils.WeakReferenceHandler

class FloatingWindowService : Service(), Handler.Callback {

    companion object {
        var weakReferenceHandler: WeakReferenceHandler? = null
    }

    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val floatView by lazy {
        val tempContainer = LinearLayout(this) // 创建一个临时的父布局
        LayoutInflater.from(this).inflate(R.layout.window_floating, tempContainer)
    }
    private val textView by lazy { floatView.findViewById<TextView>(R.id.timeView) }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        weakReferenceHandler = WeakReferenceHandler(this)
        val floatLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).also {
            windowManager.addView(floatView, it)
        }
        try {
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
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
                }
                false
            }
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: WindowManager.BadTokenException) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        windowManager.removeViewImmediate(floatView)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val time = SaveKeyValues.getValue(
            Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
        ) as String
        textView.text = time
        return START_STICKY
    }

    override fun handleMessage(msg: Message): Boolean {
        when (msg.what) {
            Constant.TICK_TIME_CODE -> {
                val time = msg.obj as Long
                textView.text = "${time}s"
            }

            Constant.UPDATE_TICK_TIME_CODE -> {
                val time = msg.obj as String
                textView.text = time
            }

            Constant.SHOW_FLOATING_WINDOW_CODE -> {
                floatView.alpha = 1.0f
            }

            Constant.HIDE_FLOATING_WINDOW_CODE -> {
                floatView.alpha = 0.0f
            }
        }
        return true
    }
}