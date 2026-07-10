package com.pengxh.kt.lite.utils.socket.web

import okhttp3.Response
import okhttp3.WebSocket
import okio.ByteString

interface OnWebSocketListener {
    fun onOpen(webSocket: WebSocket, response: Response)

    fun onDataReceived(webSocket: WebSocket, message: String)

    fun onDataReceived(webSocket: WebSocket, bytes: ByteString)

    fun onDisconnected(webSocket: WebSocket, code: Int, reason: String)

    fun onFailure(webSocket: WebSocket, t: Throwable)
}