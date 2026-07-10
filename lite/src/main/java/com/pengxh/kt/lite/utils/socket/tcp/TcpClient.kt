package com.pengxh.kt.lite.utils.socket.tcp

import android.util.Log
import io.netty.bootstrap.Bootstrap
import io.netty.channel.AdaptiveRecvByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.bytes.ByteArrayDecoder
import io.netty.handler.codec.bytes.ByteArrayEncoder
import io.netty.handler.timeout.IdleStateHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TcpClient(private val listener: OnStateChangedListener) {

    companion object {
        private const val INITIAL_IDLE_TIME = 15L
        private const val MAX_RETRY_TIMES = 10
        private const val RECONNECT_DELAY_SECONDS = 15L
        private const val RECEIVE_BUFFER_MIN = 5000
        private const val RECEIVE_BUFFER_MAX = 8000
    }

    private val kTag = "TcpClient"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val loopGroup by lazy { NioEventLoopGroup() }
    private var host: String = ""
    private var port: Int = 0
    private var needReconnect = false
    private var channel: Channel? = null
    private var isRunning = AtomicBoolean(false)
    private var retryTimes = AtomicInteger(0)

    /**
     * TcpClient 是否正在运行
     * */
    fun isRunning(): Boolean {
        return isRunning.get()
    }

    fun start(host: String, port: Int) {
        this.host = host
        this.port = port
        connect()
    }

    fun start(host: String, port: Int, force: Boolean) {
        if (force) {
            isRunning.set(false)
        }
        start(host, port)
    }

    private inner class SimpleChannelInitializer : ChannelInitializer<SocketChannel>() {
        override fun initChannel(ch: SocketChannel) {
            ch.pipeline().apply {
                addLast(ByteArrayDecoder())
                addLast(ByteArrayEncoder())
                addLast(//如果连接没有接收或发送数据超过60秒钟就发送一次心跳
                    IdleStateHandler(
                        INITIAL_IDLE_TIME, INITIAL_IDLE_TIME, 60, TimeUnit.SECONDS
                    )
                )
                addLast(object : SimpleChannelInboundHandler<ByteArray>() {
                    override fun channelActive(ctx: ChannelHandlerContext) {
                        val address = ctx.channel().remoteAddress() as InetSocketAddress
                        Log.d(kTag, "${address.address.hostAddress} 已连接")
                        listener.onConnected()
                    }

                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        val address = ctx.channel().remoteAddress() as InetSocketAddress
                        Log.d(kTag, "${address.address.hostAddress} 已断开")
                        listener.onDisconnected()
                        if (needReconnect) {
                            reconnect()
                        }
                    }

                    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteArray?) {
                        listener.onReceivedData(msg)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                        Log.d(kTag, "exceptionCaught: ${cause.message}")
                        listener.onConnectFailed()
                        ctx.close()
                        isRunning.set(false)
                    }
                })
            }
        }
    }

    @Synchronized
    private fun connect() {
        if (isRunning()) {
            Log.d(kTag, "connect: TcpClient 正在运行")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                Log.d(kTag, "开始连接: $host:$port")
                val bootstrap = createBootstrap()
                val channelFuture = bootstrap.connect(host, port).apply {
                    addListener(object : ChannelFutureListener {
                        override fun operationComplete(future: ChannelFuture) {
                            if (future.isSuccess) {
                                isRunning.set(true)
                                retryTimes.set(0)
                                channel = future.channel()
                            }
                        }
                    })
                }.sync()
                channelFuture.channel().closeFuture().sync()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            } catch (e: Exception) {
                e.printStackTrace()
                reconnect()
            }
        }
    }

    private fun createBootstrap(): Bootstrap {
        return Bootstrap().apply {
            group(loopGroup)
            channel(NioSocketChannel::class.java)
            option(ChannelOption.TCP_NODELAY, true) //无阻塞
            option(ChannelOption.SO_KEEPALIVE, true) //长连接
            option(
                ChannelOption.RCVBUF_ALLOCATOR,
                AdaptiveRecvByteBufAllocator(
                    RECEIVE_BUFFER_MIN, RECEIVE_BUFFER_MIN, RECEIVE_BUFFER_MAX
                )
            )
            option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            handler(SimpleChannelInitializer())
        }
    }

    private fun reconnect() {
        val currentRetryTimes = retryTimes.incrementAndGet()
        if (currentRetryTimes <= MAX_RETRY_TIMES) {
            Log.d(kTag, "开始第 $currentRetryTimes 次重连")
            //使用协程延迟重连，替代 Netty 的 loopGroup.schedule，防止调度器滥用。
            scope.launch(Dispatchers.Main) {
                delay(RECONNECT_DELAY_SECONDS * 1000L)
                connect()
            }
        } else {
            Log.e(kTag, "达到最大重连次数，停止重连")
            listener.onConnectFailed()
        }
    }

    fun stop(needReconnect: Boolean) {
        this.needReconnect = needReconnect
        isRunning.set(false)
        channel?.close()
    }

    fun send(msg: Any) {
        if (!isRunning()) {
            Log.d(kTag, "send: 通讯服务未连接")
            return
        }
        when (msg) {
            is ByteArray -> channel?.writeAndFlush(msg)
            is String -> channel?.writeAndFlush(msg.toByteArray(Charsets.UTF_8))
            else -> throw IllegalArgumentException("msg must be ByteArray or String")
        }
    }
}