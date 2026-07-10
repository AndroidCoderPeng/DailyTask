package com.pengxh.kt.lite.utils.socket.udp

interface OnDataReceivedListener {
    /**
     * 当接收到系统消息
     */
    fun onReceivedData(data: ByteArray)
}