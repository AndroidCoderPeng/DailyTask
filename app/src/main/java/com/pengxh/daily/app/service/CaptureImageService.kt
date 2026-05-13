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
import com.pengxh.daily.app.R
import com.pengxh.daily.app.utils.ApplicationEvent
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.HttpRequestManager
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.kt.lite.extensions.createImageFileDir
import com.pengxh.kt.lite.extensions.saveImage
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class CaptureImageService : Service(), CoroutineScope by MainScope() {

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
    private val httpRequestManager by lazy { HttpRequestManager(this) }
    private val emailManager by lazy { EmailManager(this) }
    private val mpr by lazy { getSystemService(MediaProjectionManager::class.java) }
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onCreate() {
        super.onCreate()
        EventBus.getDefault().register(this)
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
            EventBus.getDefault().post(ApplicationEvent.ProjectionFailed)
            return START_STICKY
        }

        try {
            val projection = mpr.getMediaProjection(resultCode, data)
            if (projection == null) {
                Log.w(kTag, "getMediaProjection returned null")
                EventBus.getDefault().post(ApplicationEvent.ProjectionFailed)
                return START_STICKY
            }

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    ProjectionSession.markStoppedNeedAuth()
                    SaveKeyValues.putValue(Constant.RESULT_SOURCE_KEY, 0)
                }
            }, null)

            ProjectionSession.setProjection(projection)
            Log.d(kTag, "MediaProjection created successfully")
            EventBus.getDefault().post(ApplicationEvent.ProjectionReady)
            SaveKeyValues.putValue(Constant.RESULT_SOURCE_KEY, 1)
        } catch (e: Exception) {
            Log.w(kTag, "createMediaProjection failed: ${e.message}", e)
            EventBus.getDefault().post(ApplicationEvent.ProjectionFailed)
        }

        return START_STICKY
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun handleApplicationEvent(event: ApplicationEvent) {
        if (event is ApplicationEvent.CaptureScreen) {
            captureScreen()
        }
    }

    private fun captureScreen() {
        if (!ProjectionSession.isStateActive()) {
            sendChannelMessage("MediaProjection not active. state=${ProjectionSession.getState()}")
            return
        }

        val projection = ProjectionSession.getProjection()
        if (projection == null) {
            sendChannelMessage("MediaProjection not available")
            return
        }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        releaseCaptureResources()
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        launch {
            try {
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

                // 最多等待2秒
                val image = withTimeoutOrNull(2000) {
                    waitForImageAvailable(reader)
                }

                if (image == null) {
                    sendChannelMessage("获取图像失败: acquireNextImage返回null")
                    return@launch
                }

                val width = image.width
                val height = image.height
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = createBitmap(width + rowPadding / pixelStride, height)
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

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

                val imagePath = "${createImageFileDir()}/${dateTimeFormat.format(Date())}.png"
                topHalf.saveImage(imagePath)
                EventBus.getDefault().post(ApplicationEvent.CaptureCompleted(imagePath))
            } catch (_: RemoteException) {
                ProjectionSession.markStoppedNeedAuth()
                EventBus.getDefault().post(ApplicationEvent.ProjectionFailed)
            } catch (_: SecurityException) {
                ProjectionSession.markStoppedNeedAuth()
                EventBus.getDefault().post(ApplicationEvent.ProjectionFailed)
            } catch (e: Exception) {
                sendChannelMessage("截屏失败: ${e.message}")
            } finally {
                releaseCaptureResources()
            }
        }
    }

    private suspend fun waitForImageAvailable(imageReader: ImageReader): Image? {
        return suspendCancellableCoroutine { continuation ->
            val listener = ImageReader.OnImageAvailableListener { reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    reader.setOnImageAvailableListener(null, null)
                    if (continuation.isActive) {
                        continuation.resume(image)
                    } else {
                        image.close()
                    }
                }
            }

            continuation.invokeOnCancellation {
                imageReader.setOnImageAvailableListener(null, null)
            }

            imageReader.setOnImageAvailableListener(listener, null)
        }
    }

    private fun sendChannelMessage(content: String) {
        val type = SaveKeyValues.getValue(Constant.CHANNEL_TYPE_KEY, 0) as Int
        when (type) {
            0 -> httpRequestManager.sendMessage("截屏失败", content)
            1 -> emailManager.sendEmail("截屏失败", content, false)
            else -> Log.w(kTag, "消息渠道不支持: content => $content")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(kTag, "CaptureImageService 被销毁，清理 MediaProjection")
        EventBus.getDefault().post(ApplicationEvent.ProjectionDestroyed)
        cancel()
        releaseCaptureResources()
        ProjectionSession.clear()
        SaveKeyValues.putValue(Constant.RESULT_SOURCE_KEY, 0)
        stopForeground(STOP_FOREGROUND_REMOVE)
        EventBus.getDefault().unregister(this)
    }

    private fun releaseCaptureResources() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }
}
