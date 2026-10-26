package com.pengxh.daily.app.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import com.pengxh.daily.app.R
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.ProjectionEvent
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.kt.lite.extensions.createImageFileDir
import com.pengxh.kt.lite.extensions.saveImage
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class CaptureImageService : Service(), CoroutineScope by MainScope() {

    companion object {
        private val _projectionEvents = MutableSharedFlow<ProjectionEvent>(extraBufferCapacity = 2)
        val projectionEvents = _projectionEvents.asSharedFlow()

        fun emitProjectionEvent(event: ProjectionEvent) {
            _projectionEvents.tryEmit(event)
        }

        private val captureRequestLock = Any()
        private var serviceReady = false
        private val captureRequests = Channel<CompletableDeferred<String?>>(Channel.UNLIMITED)

        /**
         * 触发截屏，返回 CompletableDeferred 供调用方 await 结果
         *
         * 每个请求携带自己的 Deferred，null 表示处理超时，空字符串表示截屏失败。
         */
        fun requestCaptureScreen(): CompletableDeferred<String?> {
            val deferred = CompletableDeferred<String?>()
            val queued = synchronized(captureRequestLock) {
                serviceReady && captureRequests.trySend(deferred).isSuccess
            }
            if (!queued) {
                deferred.complete("")
            }
            return deferred
        }
    }

    private val kTag = "CaptureImageService"
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, "capture_image_service_channel").apply {
            setSmallIcon(R.mipmap.ic_launcher)
            setContentText("截屏服务已就绪")
            setPriority(NotificationCompat.PRIORITY_LOW)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setSilent(true)
            setCategory(NotificationCompat.CATEGORY_SERVICE)
            setShowWhen(true)
            setSound(null)
            setVibrate(null)
        }
    }
    private val dateTimeFormat by lazy { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA) }
    private val mpr by lazy { getSystemService(MediaProjectionManager::class.java) }
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCapturingInitialized = false

    override fun onCreate() {
        super.onCreate()
        val name = "${resources.getString(R.string.app_name)}截屏服务"
        val channel = NotificationChannel(
            "capture_image_service_channel", name, NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Channel for Capture Image Service"
        }
        notificationManager.createNotificationChannel(channel)
        val notification = notificationBuilder.build()

        // 初始化图片文件目录
        createImageFileDir()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Constant.CAPTURE_IMAGE_SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(Constant.CAPTURE_IMAGE_SERVICE_NOTIFICATION_ID, notification)
        }

        synchronized(captureRequestLock) {
            serviceReady = true
        }
        launch {
            for (request in captureRequests) {
                if (!request.isCompleted) {
                    captureScreen(request)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: return START_STICKY

        // resultCode 为 RESULT_CANCELED 说明是服务重启（非用户授权触发），直接返回
        if (resultCode == Activity.RESULT_CANCELED) return START_STICKY

        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        }

        if (data == null) {
            Log.w(kTag, "onStartCommand: intent data is null")
            emitProjectionEvent(ProjectionEvent.Failed)
            return START_STICKY
        }

        try {
            val projection = mpr.getMediaProjection(resultCode, data)
            if (projection == null) {
                Log.w(kTag, "getMediaProjection returned null")
                emitProjectionEvent(ProjectionEvent.Failed)
                return START_STICKY
            }

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.w(kTag, "MediaProjection stopped by system")
                    ProjectionSession.markStoppedNeedAuth()
                    SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                    releaseCaptureResources()
                    isCapturingInitialized = false
                }
            }, null)

            ProjectionSession.setProjection(projection)
            Log.d(kTag, "MediaProjection created successfully")

            // 初始化截屏资源（只执行一次）
            initializeCaptureResources(projection)

            emitProjectionEvent(ProjectionEvent.Ready)

            SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 1)
        } catch (e: Exception) {
            Log.w(kTag, "createMediaProjection failed: ${e.message}", e)
            emitProjectionEvent(ProjectionEvent.Failed)
        }

        return START_STICKY
    }

    private fun initializeCaptureResources(projection: MediaProjection) {
        if (isCapturingInitialized) {
            Log.d(kTag, "Capture resources already initialized")
            return
        }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        try {
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            virtualDisplay = projection.createVirtualDisplay(
                "CaptureImageDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                reader.surface,
                null,
                null
            )

            isCapturingInitialized = true
            Log.d(kTag, "Capture resources initialized successfully")
        } catch (e: Exception) {
            Log.e(kTag, "Failed to initialize capture resources", e)
            releaseCaptureResources()
            isCapturingInitialized = false
        }
    }

    private suspend fun captureScreen(request: CompletableDeferred<String?>) {
        try {
            if (!ProjectionSession.isStateActive()) {
                request.complete("")
                return
            }

            val projection = ProjectionSession.getProjection()
            if (projection == null) {
                request.complete("")
                return
            }

            // 如果资源未初始化，先初始化
            if (!isCapturingInitialized || imageReader == null || virtualDisplay == null) {
                Log.w(kTag, "Capture resources not initialized, reinitializing...")
                // 先释放可能残留的脏资源，避免 VirtualDisplay 泄漏或状态不一致
                releaseCaptureResources()
                initializeCaptureResources(projection)
                if (!isCapturingInitialized) {
                    request.complete("")
                    return
                }
            }

            val reader = imageReader ?: run {
                request.complete("")
                return
            }
            request.complete(withTimeoutOrNull(5000L) { captureImage(reader) })
        } catch (e: CancellationException) {
            request.complete(null)
            throw e
        } catch (_: RemoteException) {
            Log.w(kTag, "RemoteException during capture")
            failProjectionCapture(request)
        } catch (_: SecurityException) {
            Log.w(kTag, "SecurityException during capture")
            failProjectionCapture(request)
        } catch (e: Exception) {
            Log.e(kTag, "截屏失败: ${e.message}", e)
            request.complete("")
        }
    }

    private suspend fun captureImage(reader: ImageReader): String {
        var retryCount = 0
        while (true) {
            val startTime = System.currentTimeMillis()
            Log.d(kTag, "================== 开始截屏 ==================")
            val image = withTimeoutOrNull(800L) {
                Log.d(kTag, "进入等待......")
                waitForImageAvailable(reader)
            } ?: run {
                Log.e(kTag, "获取图像失败，等待超时")
                return ""
            }
            Log.d(kTag, "图像获取成功, 耗时: ${System.currentTimeMillis() - startTime}ms")

            val width = image.width
            val height = image.height
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width
            val bitmap = createBitmap(width + rowPadding / pixelStride, height)
            try {
                bitmap.copyPixelsFromBuffer(buffer)
            } finally {
                image.close()
            }

            val cropped = if (rowPadding != 0) {
                Bitmap.createBitmap(bitmap, 0, 0, width, height)
            } else bitmap

            // 只取中间那部分截图
            val y = (cropped.height * 0.2f).toInt()
            val halfHeight = y + cropped.height / 2

            // 边界检查
            val validY = y.coerceAtLeast(0).coerceAtMost(cropped.height - 1)
            val validHeight = halfHeight.coerceAtLeast(1).coerceAtMost(cropped.height - validY)
            val topHalf = if (validY >= 0 && validHeight > 0
                && validY + validHeight <= cropped.height
            ) {
                Bitmap.createBitmap(cropped, 0, validY, cropped.width, validHeight)
            } else {
                cropped
            }

            if (isBitmapMostlyBlack(topHalf) && retryCount < 2) {
                retryCount++
                Log.w(kTag, "检测到黑色画面，第${retryCount}次重试")
                delay(1000)
                continue
            }
            if (retryCount >= 2) {
                Log.w(kTag, "黑色画面重试已耗尽，使用当前图像")
            }

            val imagePath = "${createImageFileDir()}/${dateTimeFormat.format(Date())}.png"
            topHalf.saveImage(imagePath)
            LogFileManager.writeLog("截屏成功: $imagePath")
            return imagePath
        }
    }

    private fun failProjectionCapture(request: CompletableDeferred<String?>) {
        ProjectionSession.markStoppedNeedAuth()
        emitProjectionEvent(ProjectionEvent.Failed)
        releaseCaptureResources()
        isCapturingInitialized = false
        request.complete("")
    }

    private suspend fun waitForImageAvailable(imageReader: ImageReader): Image? {
        return suspendCancellableCoroutine { continuation ->
            var resumed = false

            val listener = ImageReader.OnImageAvailableListener { reader ->
                if (resumed) return@OnImageAvailableListener
                // acquireLatestImage 在部分国内 OEM 后台场景可能返回 null，用 acquireNextImage 兜底
                val image = reader.acquireLatestImage() ?: reader.acquireNextImage()
                if (image != null) {
                    reader.setOnImageAvailableListener(null, null)
                    resumed = true
                    if (continuation.isActive) {
                        continuation.resume(image)
                    } else {
                        image.close()
                    }
                }
                // image 为 null 时保留 listener，等待下一次回调；超时由 withTimeoutOrNull 兜底
            }

            continuation.invokeOnCancellation {
                if (!resumed) {
                    imageReader.setOnImageAvailableListener(null, null)
                }
            }

            imageReader.setOnImageAvailableListener(listener, null)

            // 立即尝试获取 buffer 中已有的帧（不依赖 listener 异步回调），后台慢帧率场景下 buffer 里大概率已有一帧，直接取可避免超时
            if (!resumed) {
                val existing = imageReader.acquireLatestImage()
                if (existing != null) {
                    imageReader.setOnImageAvailableListener(null, null)
                    resumed = true
                    continuation.resume(existing)
                }
            }
        }
    }

    override fun onDestroy() {
        synchronized(captureRequestLock) {
            serviceReady = false
            while (true) {
                val request = captureRequests.tryReceive().getOrNull() ?: break
                request.complete(null)
            }
        }
        super.onDestroy()
        cancel()
        releaseCaptureResources()
        ProjectionSession.clear()
        SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
        isCapturingInitialized = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun isBitmapMostlyBlack(bitmap: Bitmap): Boolean {
        val sampleStep = 10
        val darkThreshold = 20
        var darkPixels = 0
        var totalPixels = 0
        for (y in 0 until bitmap.height step sampleStep) {
            for (x in 0 until bitmap.width step sampleStep) {
                val pixel = bitmap[x, y]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r < darkThreshold && g < darkThreshold && b < darkThreshold) {
                    darkPixels++
                }
                totalPixels++
            }
        }
        return totalPixels > 0 && darkPixels.toFloat() / totalPixels >= 0.90f
    }

    private fun releaseCaptureResources() {
        Log.d(kTag, "Releasing capture resources")
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }
}
