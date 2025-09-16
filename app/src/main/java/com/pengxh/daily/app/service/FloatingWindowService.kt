package com.pengxh.daily.app.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.utils.SaveKeyValues

class FloatingWindowService : Service() {

    private val kTag = "FloatingWindowService"
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val floatView by lazy {
        val tempContainer = LinearLayout(this) // 创建一个临时的父布局
        LayoutInflater.from(this).inflate(R.layout.window_floating, tempContainer)
    }
    private val textView by lazy { floatView.findViewById<TextView>(R.id.timeView) }
    private var broadcastReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
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
        ) as String
        textView.text = time
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

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun initBroadcastReceiver() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(kTag, "onReceive: ${intent?.action}")
                when (intent?.action) {
                    Constant.BROADCAST_TICK_TIME_ACTION -> {
                        val time = intent.getStringExtra("data")
                        textView.text = "${time}s"
                    }

                    Constant.BROADCAST_UPDATE_TICK_TIME_ACTION -> {
                        val time = intent.getStringExtra("data")
                        textView.text = time
                    }

                    Constant.BROADCAST_SHOW_FLOATING_WINDOW_ACTION -> {
                        floatView.alpha = 1.0f
                        val time = SaveKeyValues.getValue(
                            Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
                        ) as String
                        textView.text = time
                    }

                    Constant.BROADCAST_HIDE_FLOATING_WINDOW_ACTION -> {
                        floatView.alpha = 0.0f
                    }
                }
            }
        }
        val intentFilter = IntentFilter().apply {
            addAction(Constant.BROADCAST_TICK_TIME_ACTION)
            addAction(Constant.BROADCAST_UPDATE_TICK_TIME_ACTION)
            addAction(Constant.BROADCAST_SHOW_FLOATING_WINDOW_ACTION)
            addAction(Constant.BROADCAST_HIDE_FLOATING_WINDOW_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, intentFilter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        broadcastReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
        }
        broadcastReceiver = null
        windowManager.removeViewImmediate(floatView)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}