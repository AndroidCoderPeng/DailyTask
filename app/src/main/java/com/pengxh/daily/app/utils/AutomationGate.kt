package com.pengxh.daily.app.utils

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 设备执行权：同一时刻只允许一条流程驱动目标 App、悬浮窗倒计时与截屏会话。
 * 规则：谁先到谁先执行，后到的排队等待，互斥但不抢占。
 * 因此定时任务与远程打卡指令不会同时操作设备，倒计时不会错乱、截屏不会互抢。
 */
object AutomationGate {

    enum class Owner { SCHEDULED, REMOTE }

    // 判定"最近发生过远程打卡"的时间窗
    private const val RECENT_REMOTE_CLOCK_IN_WINDOW_MILLIS = 10 * 60 * 1000L

    private val mutex = Mutex()

    // 最近一次远程指令占用设备的区间，用于判定"打卡成功通知"归谁
    @Volatile
    private var remoteDriveStartMillis = 0L

    @Volatile
    private var remoteDriveEndMillis = 0L

    // 远程打卡指令的时刻与排队状态
    @Volatile
    private var remoteClockInAtMillis = 0L

    @Volatile
    private var remoteClockInPending = false

    // 设备当前是否被占用（用于提示用户需排队等待）
    fun isBusy(): Boolean = mutex.isLocked

    // 远程打卡指令已受理
    fun markRemoteClockInQueued() {
        remoteClockInPending = true
        remoteClockInAtMillis = System.currentTimeMillis()
    }

    // 远程打卡指令执行完毕
    fun markRemoteClockInFinished() {
        remoteClockInPending = false
    }

    // 最近是否发生过远程打卡指令（定时任务据此提示"已被手动指令打卡"）
    fun hasRecentRemoteClockIn(): Boolean {
        if (remoteClockInPending) return true
        val at = remoteClockInAtMillis
        return at > 0L &&
                System.currentTimeMillis() - at <= RECENT_REMOTE_CLOCK_IN_WINDOW_MILLIS
    }

    // 持有设备执行权执行一段逻辑
    suspend fun <T> withDevice(owner: Owner, block: suspend () -> T): T =
        mutex.withLock {
            if (owner == Owner.REMOTE) {
                remoteDriveStartMillis = System.currentTimeMillis()
                remoteDriveEndMillis = 0L
            }
            try {
                block()
            } finally {
                if (owner == Owner.REMOTE) {
                    remoteDriveEndMillis = System.currentTimeMillis()
                }
            }
        }

    // 该"打卡成功"通知是否属于远程指令（远程指令已代打卡，定时任务不应计入）
    fun belongsToRemoteDrive(postedAtMillis: Long): Boolean {
        if (remoteDriveStartMillis <= 0L) return false
        val end = if (remoteDriveEndMillis > 0L) {
            remoteDriveEndMillis
        } else {
            System.currentTimeMillis()
        }
        return postedAtMillis >= remoteDriveStartMillis && postedAtMillis <= end
    }
}
