package com.pengxh.daily.app.utils

object NotificationListenerState {

    @Volatile
    private var connected = false

    @Volatile
    private var lastActiveAt = 0L

    fun markConnected() {
        connected = true
        lastActiveAt = System.currentTimeMillis()
    }

    fun markDisconnected(): Boolean {
        val wasConnected = connected
        connected = false
        lastActiveAt = System.currentTimeMillis()
        return wasConnected
    }

    fun isConnected(): Boolean = connected

    fun lastActiveTime(): Long = lastActiveAt
}
