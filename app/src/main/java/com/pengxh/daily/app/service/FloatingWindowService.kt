package com.pengxh.daily.app.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Service
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
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.HttpRequestManager
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class FloatingWindowService : Service(), CoroutineScope by CoroutineScope(Dispatchers.Main) {
    private val kTag = "FloatingWindowService"
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val activityManager by lazy { getSystemService(ActivityManager::class.java) }
    private val httpRequestManager by lazy { HttpRequestManager(this) }
    private val emailManager by lazy { EmailManager(this) }
    private lateinit var binding: WindowFloatingBinding
    private var floatViewParams: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var memoryMonitorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        binding = WindowFloatingBinding.inflate(LayoutInflater.from(this))

        EventBus.getDefault().register(this)

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

        startMemoryMonitoring()
    }

    private fun startMemoryMonitoring() {
        val mode = SaveKeyValues.getValue(Constant.POWER_SAVE_MODE_KEY, false) as Boolean
        val interval = if (mode) {
            60_000L
        } else {
            1_000L
        }
        memoryMonitorJob = launch {
            // 立即更新一次
            updateMemoryInfo()

            while (isActive) {
                delay(interval)
                updateMemoryInfo()
            }
        }
    }

    private suspend fun updateMemoryInfo() {
        withContext(Dispatchers.IO) {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            val totalMem = memoryInfo.totalMem
            val availMem = memoryInfo.availMem
            val usedMem = totalMem - availMem
            val usagePercent = ((usedMem * 100.0) / totalMem).toInt()

            withContext(Dispatchers.Main) {
                binding.waveProgressView.setProgress(usagePercent)
                if (usagePercent >= 90) {
                    sendChannelMessage()
                }
            }
        }
    }

    private fun sendChannelMessage() {
        val title = "内存使用预警"
        val content = "当前内存使用已超过90%，请关注设备运行情况"
        val type = SaveKeyValues.getValue(Constant.CHANNEL_TYPE_KEY, 0) as Int
        when (type) {
            0 -> httpRequestManager.sendMessage(title, content)
            1 -> emailManager.sendEmail(title, content, false)
            else -> Log.d(kTag, "sendChannelMessage: 消息渠道不支持")
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun handleApplicationEvent(event: ApplicationEvent) {
        when (event) {
            is ApplicationEvent.ShowFloatingWindow -> {
                binding.root.alpha = 1.0f
                val time = SaveKeyValues.getValue(
                    Constant.STAY_DD_TIMEOUT_KEY, Constant.DEFAULT_OVER_TIME
                ) as Int
                binding.timeView.text = "${time}s"
            }

            is ApplicationEvent.HideFloatingWindow -> {
                binding.root.alpha = 0.0f
                binding.timeView.text = "0s"
            }

            is ApplicationEvent.SetTaskOvertime -> {
                // 更新目标应用任务超时时间
                binding.timeView.text = "${event.time}s"
            }

            is ApplicationEvent.UpdateFloatingViewTime -> {
                // 更新悬浮窗倒计时
                binding.timeView.text = "${event.tick}s"
                if (event.tick < 1) {
                    binding.root.alpha = 0.0f
                } else {
                    binding.root.alpha = 1.0f
                }
            }

            else -> {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onDragMove() {
        binding.root.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                event ?: return false
                when (event.action) {
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
        memoryMonitorJob?.cancel()
        cancel()
        EventBus.getDefault().unregister(this)
        if (::binding.isInitialized && binding.root.isAttachedToWindow) {
            try {
                windowManager.removeViewImmediate(binding.root)
            } catch (e: IllegalArgumentException) {
                Log.w(kTag, "View not attached to window manager", e)
            }
        }
        Log.d(kTag, "onDestroy: FloatingWindowService")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}