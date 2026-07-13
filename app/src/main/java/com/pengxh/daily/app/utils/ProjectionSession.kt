package com.pengxh.daily.app.utils

import android.media.projection.MediaProjection
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

object ProjectionSession {
    private const val kTag = "ProjectionSession"

    enum class State { IDLE, ACTIVE, NEED_AUTH }

    private val projectionRef = AtomicReference<MediaProjection?>(null)

    @Volatile
    private var state = State.IDLE

    // 单字段读取，不需要同步
    fun isStateActive(): Boolean = state == State.ACTIVE

    // 同上
    fun getState(): State = state

    fun setProjection(projection: MediaProjection) {
        synchronized(this) {
            projectionRef.getAndSet(projection)?.let {
                try {
                    it.stop()
                } catch (e: Throwable) {
                    Log.w(kTag, "stop old projection failed", e)
                }
            }
            state = State.ACTIVE
        }
    }

    // 同上
    fun getProjection(): MediaProjection? = projectionRef.get()

    fun markStoppedNeedAuth() {
        synchronized(this) {
            state = State.NEED_AUTH
            projectionRef.getAndSet(null)
        }
    }

    fun clear() {
        synchronized(this) {
            projectionRef.getAndSet(null)?.let {
                try {
                    it.stop()
                } catch (_: Throwable) {
                    // ignore
                }
            }
            state = State.IDLE
        }
    }
}