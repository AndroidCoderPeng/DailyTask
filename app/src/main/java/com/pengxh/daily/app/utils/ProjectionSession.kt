package com.pengxh.daily.app.utils

import android.media.projection.MediaProjection
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

object ProjectionSession {

    private const val kTag = "ProjectionSession"

    enum class State {
        IDLE,
        ACTIVE,
        NEED_AUTH
    }

    private val projectionRef = AtomicReference<MediaProjection?>(null)

    private var state = State.IDLE

    fun isStateActive(): Boolean {
        return synchronized(this) {
            state == State.ACTIVE
        }
    }

    fun getState(): State {
        return synchronized(this) {
            state
        }
    }

    fun setProjection(projection: MediaProjection) {
        projectionRef.getAndSet(projection)?.let {
            try {
                it.stop()
            } catch (e: Throwable) {
                Log.w(kTag, "stop old projection failed", e)
            }
        }
        state = State.ACTIVE
    }

    fun getProjection(): MediaProjection? = projectionRef.get()

    fun markStoppedNeedAuth() {
        state = State.NEED_AUTH
        projectionRef.getAndSet(null)
    }

    fun clear() {
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