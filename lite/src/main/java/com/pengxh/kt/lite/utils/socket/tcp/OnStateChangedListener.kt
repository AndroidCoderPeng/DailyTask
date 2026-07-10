package com.pengxh.kt.lite.utils.socket.tcp

interface OnStateChangedListener {
    fun onConnected()
    fun onDisconnected()
    fun onConnectFailed()
    fun onReceivedData(bytes: ByteArray?)
}