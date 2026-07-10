package com.pengxh.kt.lite.utils.socket.udp

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramChannel
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.util.CharsetUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.InetSocketAddress

class UdpClient(private val listener: OnDataReceivedListener) {

    companion object {
        // 默认缓冲区大小
        private const val RECEIVE_BUFFER_SIZE = 1024
        private const val SEND_BUFFER_SIZE = 1024
    }

    private val kTag = "UdpClient"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val loopGroup by lazy { NioEventLoopGroup() }
    private lateinit var socketAddress: InetSocketAddress
    private var channel: Channel? = null

    private inner class SimpleChannelInitializer : ChannelInitializer<DatagramChannel>() {
        override fun initChannel(dc: DatagramChannel) {
            dc.pipeline().apply {
                addLast(object : SimpleChannelInboundHandler<DatagramPacket>() {
                    override fun channelRead0(ctx: ChannelHandlerContext?, msg: DatagramPacket) {
                        val byteBuf = msg.content()
                        val byteArray = ByteArray(byteBuf.readableBytes()).apply {
                            byteBuf.readBytes(this)
                        }
                        listener.onReceivedData(byteArray)
                    }
                })
            }
        }
    }

    fun bind(remote: String, port: Int) {
        this.socketAddress = InetSocketAddress(remote, port)
        scope.launch(Dispatchers.IO) {
            try {
                val bootstrap = createBootstrap()
                val channelFuture = bootstrap.bind(port).apply {
                    addListener(object : ChannelFutureListener {
                        override fun operationComplete(future: ChannelFuture) {
                            if (future.isSuccess) {
                                channel = future.channel()
                            }
                        }
                    })
                }.sync()
                channelFuture.channel().closeFuture().sync()
            } catch (e: Exception) {
                e.printStackTrace()
                release()
            }
        }
    }

    private fun createBootstrap(): Bootstrap {
        return Bootstrap().apply {
            group(loopGroup)
            channel(NioDatagramChannel::class.java)
            option(ChannelOption.SO_RCVBUF, RECEIVE_BUFFER_SIZE)
            option(ChannelOption.SO_SNDBUF, SEND_BUFFER_SIZE)
            handler(SimpleChannelInitializer())
        }
    }

    fun send(msg: Any) {
        when (msg) {
            is ByteArray -> {
                val byteBuf = Unpooled.copiedBuffer(msg)
                writeAndFlush(byteBuf)
            }

            is String -> {
                val byteBuf = Unpooled.copiedBuffer(msg, CharsetUtil.UTF_8)
                writeAndFlush(byteBuf)
            }

            is ByteBuf -> writeAndFlush(msg)

            else -> throw IllegalArgumentException("Unsupported message type: ${msg.javaClass.name}")
        }
    }

    private fun writeAndFlush(byteBuf: ByteBuf) {
        val datagramPacket = DatagramPacket(byteBuf, socketAddress)
        channel?.writeAndFlush(datagramPacket)
    }

    /**
     * 释放资源
     */
    fun release() {
        loopGroup.shutdownGracefully()
        scope.cancel()
    }
}