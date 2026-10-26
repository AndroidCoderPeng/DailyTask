package com.pengxh.daily.app.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.pengxh.daily.app.databinding.WindowFloatingBinding
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.utils.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

class FloatingWindowService : Service(), CoroutineScope by CoroutineScope(Dispatchers.Main) {

    companion object {
        private const val MEMORY_WARNING_INTERVAL_MILLIS = 30 * 60 * 1000L
        private val powerSaveModeUpdates = Channel<Unit>(Channel.CONFLATED)

        fun notifyPowerSaveModeChanged() {
            powerSaveModeUpdates.trySend(Unit)
        }
    }

    private val kTag = "FloatingWindowService"
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val activityManager by lazy { getSystemService(ActivityManager::class.java) }
    private lateinit var binding: WindowFloatingBinding
    private var floatViewParams: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var memoryMonitorJob: Job? = null
    private var lastMemoryWarningTime: Long? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        binding = WindowFloatingBinding.inflate(LayoutInflater.from(this))
        floatViewParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }.also {
            windowManager.addView(binding.root, it)
        }

        // 布局完成后，将悬浮窗移动到屏幕右侧垂直居中
        binding.root.post {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val viewWidth = binding.root.width
            val viewHeight = binding.root.height

            floatViewParams?.let {
                it.x = screenWidth - viewWidth
                it.y = (screenHeight - viewHeight) / 2
                windowManager.updateViewLayout(binding.root, it)
            }
        }

        // 收集悬浮窗控制事件
        launch {
            FloatingWindowController.timeTick.collect { tick ->
                binding.timeView.text = "${tick}s"
                binding.root.alpha = if (tick < 1) 0.0f else 1.0f
            }
        }
        launch {
            FloatingWindowController.overtime.collect { seconds ->
                binding.timeView.text = "${seconds}s"
            }
        }
        launch {
            FloatingWindowController.visibility.collect { visible ->
                if (visible) {
                    binding.root.alpha = 1.0f
                    val time = AppConfig.loadInt(
                        Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
                    )
                    binding.timeView.text = "${time}s"
                } else {
                    binding.root.alpha = 0.0f
                    binding.timeView.text = "0s"
                }
            }
        }

        // 获取目标应用任务超时时间
        val time = AppConfig.loadInt(Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME)
        binding.timeView.text = "${time}s"

        // 移动悬浮窗
        onDragMove()

        startMemoryMonitoring()
    }

    private fun startMemoryMonitoring() {
        memoryMonitorJob = launch {
            // 立即更新一次
            updateMemoryInfo()

            while (isActive) {
                val mode = AppConfig.loadBoolean(Constant.POWER_SAVE_MODE_KEY, false)
                val interval = if (mode) 60_000L else 1_000L
                withTimeoutOrNull(interval) {
                    powerSaveModeUpdates.receive()
                }
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
            // availMem 包含大量可瞬间回收的缓存，用 total-avail 算"已用"会严重虚高，
            // 正常手机几乎必然 ≥90% 而误报。改用系统自身的低内存判定：可用内存低于系统临界值。
            val lowMemory = memoryInfo.availMem <= memoryInfo.threshold

            withContext(Dispatchers.Main) {
                binding.waveProgressView.setProgress(usagePercent)
                if (lowMemory) {
                    val now = SystemClock.elapsedRealtime()
                    val lastWarning = lastMemoryWarningTime
                    if (lastWarning == null || now - lastWarning >= MEMORY_WARNING_INTERVAL_MILLIS) {
                        MessageDispatcher.sendMessage(
                            "内存使用预警",
                            "系统可用内存已低于临界值（低内存状态），请关注设备运行情况",
                            onFailure = {
                                ForegroundRunningService.emitNotificationText("内存预警发送失败：$it")
                            }
                        )
                        lastMemoryWarningTime = now
                    }
                } else {
                    lastMemoryWarningTime = null
                }
            }
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
