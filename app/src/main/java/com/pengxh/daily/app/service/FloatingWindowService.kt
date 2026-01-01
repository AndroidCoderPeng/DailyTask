package com.pengxh.daily.app.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.pengxh.daily.app.databinding.WindowFloatingBinding
import com.pengxh.daily.app.utils.BroadcastManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.MessageType
import com.pengxh.kt.lite.utils.SaveKeyValues

class FloatingWindowService : Service() {
    private val kTag = "FloatingWindowService"
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private lateinit var binding: WindowFloatingBinding
    private var floatViewParams: WindowManager.LayoutParams? = null
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
    private val broadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.action?.let {
                    when (MessageType.fromAction(it)) {
                        MessageType.SHOW_FLOATING_WINDOW -> {
                            binding.root.alpha = 1.0f
                            val time = SaveKeyValues.getValue(
                                Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
                            ) as Int
                            binding.timeView.text = "${time}s"
                        }

                        MessageType.HIDE_FLOATING_WINDOW -> {
                            binding.root.alpha = 0.0f
                            binding.timeView.text = "0s"
                        }

                        MessageType.SET_DING_DING_OVERTIME -> {
                            // 更新目标应用任务超时时间
                            val time = intent.getIntExtra("time", 30)
                            binding.timeView.text = "${time}s"
                        }

                        MessageType.UPDATE_FLOATING_WINDOW_TIME -> {
                            // 更新悬浮窗倒计时
                            val tick = intent.getLongExtra("tick", 30)
                            binding.timeView.text = "${tick}s"
                            if (tick < 1) {
                                binding.root.alpha = 0.0f
                            } else {
                                binding.root.alpha = 1.0f
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

    override fun onCreate() {
        super.onCreate()
        binding = WindowFloatingBinding.inflate(LayoutInflater.from(this))

        BroadcastManager.getDefault().registerReceivers(this, actions, broadcastReceiver)

        floatViewParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER or Gravity.TOP
        }.also {
            windowManager.addView(binding.root, it)
        }

        // 获取目标应用任务超时时间
        val time = SaveKeyValues.getValue(
            Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
        ) as Int
        binding.timeView.text = "${time}s"

        // 移动悬浮窗
        onDragMove()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onDragMove() {
        binding.root.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = floatViewParams?.x ?: 0
                        initialY = floatViewParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        floatViewParams?.let {
                            it.x = initialX + (event.rawX - initialTouchX).toInt()
                            it.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(binding.root, it)
                        }
                        return true
                    }

                    else -> return false
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        actions.forEach {
            BroadcastManager.getDefault().unregisterReceiver(this, it)
        }
        if (::binding.isInitialized && binding.root.isAttachedToWindow) {
            try {
                windowManager.removeViewImmediate(binding.root)
            } catch (e: IllegalArgumentException) {
                Log.w(kTag, "View not attached to window manager", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}