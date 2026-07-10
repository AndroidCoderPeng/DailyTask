package com.pengxh.kt.lite.utils.socket.web

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WebSocketClient(private val listener: OnWebSocketListener) {

    companion object {
        private const val MAX_RETRY_TIMES = 10
        private const val RECONNECT_DELAY_TIME = 15 * 1000L
    }

    private val kTag = "WebSocketClient"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder().readTimeout(15, TimeUnit.SECONDS).build()
    private lateinit var url: String
    private lateinit var webSocket: WebSocket
    private var needReconnect = true
    private var isRunning = AtomicBoolean(false)
    private var retryTimes = AtomicInteger(0)

    /**
     * WebSocketClient 是否正在运行
     * */
    fun isRunning(): Boolean {
        return isRunning.get()
    }


    private fun isValidWebSocketUrl(url: String): Boolean {
        return url.isNotEmpty() && (url.startsWith("ws://") || url.startsWith("wss://"))
    }

    fun start(url: String) {
        this.url = url
        connect()
    }

    fun start(url: String, force: Boolean) {
        if (force) {
            isRunning.set(false)
            webSocket.close(1000, "")
        }
        start(url)
    }

    @Synchronized
    private fun connect() {
        if (!isValidWebSocketUrl(url)) {
            Log.e(kTag, "Invalid URL: $url")
            return
        }

        if (isRunning()) {
            Log.d(kTag, "connect: WebSocketClient 正在运行")
            return
        }

        Log.d(kTag, "开始连接: $url")
        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                Log.d(kTag, "onOpen: WebSocket已连接")
                listener.onOpen(webSocket, response)
                isRunning.set(true)
                retryTimes.set(0)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                listener.onDataReceived(webSocket, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                super.onMessage(webSocket, bytes)
                listener.onDataReceived(webSocket, bytes)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                Log.d(kTag, "onOpen: WebSocket已断开")
                listener.onDisconnected(webSocket, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                Log.d(kTag, "exceptionCaught: ${t.message}")
                listener.onFailure(webSocket, t)
                webSocket.close(1000, "")
                isRunning.set(false)
                if (needReconnect) {
                    reconnect()
                }
            }
        })
    }

    private fun reconnect() {
        scope.launch {
            try {
                if (retryTimes.get() <= MAX_RETRY_TIMES) {
                    val currentRetryTimes = retryTimes.incrementAndGet()
                    Log.d(kTag, "开始第 $currentRetryTimes 次重连")
                    delay(RECONNECT_DELAY_TIME)
                    withContext(Dispatchers.IO) { connect() }
                } else {
                    Log.e(kTag, "达到最大重连次数，停止重连")
                }
            } catch (e: Exception) {
                Log.e(kTag, "重连失败", e)
                isRunning.set(false)
            }
        }
    }

    /**
     * 1000 indicates a normal closure, meaning that the purpose for which the connection was established has been fulfilled
     * */
    fun stop(needReconnect: Boolean) {
        Log.d(kTag, "$url 断开连接")
        this.needReconnect = needReconnect
        if (::webSocket.isInitialized) {
            webSocket.close(1000, "Application Request Close")
        }
        isRunning.set(false)
    }
}