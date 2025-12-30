package com.pengxh.daily.app.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import java.io.Serializable

/**
 * 广播管理器
 * 提供统一的广播注册、注销、发送功能
 */
class BroadcastManager {

    companion object {
        @Volatile
        private var INSTANCE: BroadcastManager? = null

        fun getDefault (): BroadcastManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BroadcastManager().also { INSTANCE = it }
            }
        }
    }

    private val kTag = "BroadcastManager"
    private val receivers = mutableMapOf<String, BroadcastReceiver>()

    /**
     * 注册广播接收器
     */
    fun registerReceiver(
        context: Context,
        action: String,
        receiver: BroadcastReceiver
    ) {
        synchronized(receivers) {
            if (receivers.containsKey(action)) {
                // 先注销已存在的
                unregisterReceiver(context, action)
            }

            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            receivers[action] = receiver
        }
    }

    /**
     * 批量注册广播接收器
     */
    fun registerReceivers(
        context: Context,
        actions: List<String>,
        receiver: BroadcastReceiver
    ) {
        actions.forEach { action ->
            registerReceiver(context, action, receiver)
        }
    }

    /**
     * 注销广播接收器
     */
    fun unregisterReceiver(context: Context, action: String) {
        synchronized(receivers) {
            receivers[action]?.let { receiver ->
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(kTag, "unregisterReceiver: ", e)
                }
                receivers.remove(action)
            }
        }
    }

    /**
     * 注销所有广播接收器
     */
    fun unregisterAll(context: Context) {
        synchronized(receivers) {
            receivers.forEach { (_, receiver) ->
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(kTag, "unregisterAll: ", e)
                }
            }
            receivers.clear()
        }
    }

    /**
     * 发送广播
     */
    fun sendBroadcast(
        context: Context,
        action: String,
        extras: Map<String, Any>? = null
    ) {
        val intent = Intent(action)
        extras?.forEach { (key, value) ->
            when (value) {
                is String -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Float -> intent.putExtra(key, value)
                is Double -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Serializable -> intent.putExtra(key, value)
            }
        }
        context.sendBroadcast(intent)
    }

    /**
     * 发送有序广播
     */
    fun sendOrderedBroadcast(
        context: Context,
        action: String,
        extras: Map<String, Any>? = null,
        receiverPermission: String? = null
    ) {
        val intent = Intent(action)
        extras?.forEach { (key, value) ->
            when (value) {
                is String -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Float -> intent.putExtra(key, value)
                is Double -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Serializable -> intent.putExtra(key, value)
            }
        }
        context.sendOrderedBroadcast(intent, receiverPermission)
    }
}